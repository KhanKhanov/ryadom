package ru.ryadom.backend.requests

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.db.HelpRequestsTable
import ru.ryadom.backend.db.RatingsTable
import ru.ryadom.backend.db.RequestNotificationsTable
import ru.ryadom.backend.db.UNIQUE_VIOLATION
import ru.ryadom.backend.db.dbValue
import ru.ryadom.backend.db.genderPreferenceFromDb
import ru.ryadom.backend.db.languageFromDb
import ru.ryadom.backend.db.requestStatusFromDb
import ru.ryadom.backend.db.toDb
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.RequestStatus
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

/** Запрос помощи, как он хранится в базе. */
data class HelpRequestRecord(
    val id: Uuid,
    val blindUserId: Uuid,
    val language: Language,
    val genderPreference: GenderPreference,
    val status: RequestStatus,
    /** Волонтёр, который принял запрос. */
    val acceptedBy: Uuid?,
    /** Номер следующей волны уведомлений: 0 — ещё ни одной не отправлено. */
    val nextWave: Int,
    val createdAt: Instant,
    val acceptedAt: Instant?,
    val endedAt: Instant?,
) {
    /** Участник звонка: незрячий, создавший запрос, или волонтёр, который его принял. */
    fun isParticipant(userId: Uuid): Boolean = userId == blindUserId || userId == acceptedBy
}

sealed interface CreateResult {
    data class Created(
        val request: HelpRequestRecord,
    ) : CreateResult

    data object ActiveRequestExists : CreateResult

    data object RateLimited : CreateResult
}

sealed interface AcceptResult {
    /** Волонтёр принял запрос. [otherWaiting] — остальные уведомлённые волонтёры, которые ещё не ответили. */
    data class Accepted(
        val request: HelpRequestRecord,
        val otherWaiting: List<Uuid>,
    ) : AcceptResult

    /** Запрос уже не ищет волонтёра: его приняли (возможно, этот же волонтёр) или закрыли. */
    data class NotSearching(
        val request: HelpRequestRecord,
    ) : AcceptResult

    /** Волонтёру не приходило уведомление об этом запросе. */
    data object NotNotified : AcceptResult

    /** У волонтёра уже идёт другой звонок. */
    data object VolunteerBusy : AcceptResult
}

/** Что известно о волонтёрах для подбора к запросу. */
data class VolunteerActivity(
    /** Когда волонтёра в последний раз беспокоили: вызов (по любому запросу) или конец его звонка. */
    val lastDisturbedAt: Map<Uuid, Instant>,
    /** Волонтёры, у которых сейчас идёт звонок. */
    val inCall: Set<Uuid>,
    /** Волонтёры, которым уже приходило уведомление об этом запросе. */
    val notifiedForRequest: Set<Uuid>,
)

/**
 * Запросы помощи, уведомления волонтёров и оценки в базе.
 * Все смены статуса — условные (`UPDATE ... WHERE status IN (...)`): при одновременных действиях
 * срабатывает только первое, а правила «один активный запрос» и «один звонок у волонтёра»
 * дополнительно защищены уникальными индексами (миграция V2).
 */
class HelpRequestRepository(
    private val db: AppDatabase,
) {
    /** Создаёт запрос в статусе `searching`, если у пользователя нет активного и не превышен лимит. */
    suspend fun create(
        blindUserId: Uuid,
        language: Language,
        genderPreference: GenderPreference,
        now: Instant,
        maxRequests: Int,
        window: Duration,
    ): CreateResult =
        try {
            db.query {
                if (findOne { (HelpRequestsTable.blindUserId eq blindUserId) and activeRequest() } != null) {
                    return@query CreateResult.ActiveRequestExists
                }
                val windowStart = now.minus(window).toDb()
                val recent =
                    HelpRequestsTable
                        .selectAll()
                        .where { (HelpRequestsTable.blindUserId eq blindUserId) and (HelpRequestsTable.createdAt greater windowStart) }
                        .count()
                if (recent >= maxRequests) return@query CreateResult.RateLimited
                val id = Uuid.random()
                HelpRequestsTable.insert {
                    it[this.id] = id
                    it[this.blindUserId] = blindUserId
                    it[this.language] = language.dbValue
                    it[this.genderPreference] = genderPreference.toDb()
                    it[status] = RequestStatus.SEARCHING.dbValue
                    it[nextWave] = 0
                    it[createdAt] = now.toDb()
                }
                CreateResult.Created(checkNotNull(findOne { HelpRequestsTable.id eq id }))
            }
        } catch (e: ExposedSQLException) {
            // Одновременный запрос того же пользователя успел создать активный запрос раньше.
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            CreateResult.ActiveRequestExists
        }

    suspend fun findById(id: Uuid): HelpRequestRecord? = db.query { findOne { HelpRequestsTable.id eq id } }

    /** Активный запрос незрячего. */
    suspend fun findActiveByBlind(userId: Uuid): HelpRequestRecord? =
        db.query { findOne { (HelpRequestsTable.blindUserId eq userId) and activeRequest() } }

    /** Принятый волонтёром запрос, звонок по которому ещё не завершён. */
    suspend fun findActiveByVolunteer(userId: Uuid): HelpRequestRecord? =
        db.query { findOne { (HelpRequestsTable.acceptedBy eq userId) and ongoingCall() } }

    /** Есть ли у пользователя активный запрос (как у незрячего) или идущий звонок (как у волонтёра). */
    suspend fun hasActiveRequestOrCall(userId: Uuid): Boolean =
        db.query {
            !HelpRequestsTable
                .selectAll()
                .where {
                    ((HelpRequestsTable.blindUserId eq userId) and activeRequest()) or
                        ((HelpRequestsTable.acceptedBy eq userId) and ongoingCall())
                }.empty()
        }

    /** Идущие поиски, о которых волонтёру приходил вызов и на которые он ещё не отвечал, — от старых к новым. */
    suspend fun findIncoming(volunteerId: Uuid): List<HelpRequestRecord> =
        db.query {
            HelpRequestsTable
                .join(RequestNotificationsTable, JoinType.INNER, HelpRequestsTable.id, RequestNotificationsTable.requestId)
                .selectAll()
                .where {
                    (RequestNotificationsTable.volunteerId eq volunteerId) and
                        RequestNotificationsTable.result.isNull() and
                        (HelpRequestsTable.status eq RequestStatus.SEARCHING.dbValue)
                }.orderBy(HelpRequestsTable.createdAt)
                .map { it.toRecord() }
        }

    /** Запросы, для которых идёт поиск волонтёра. */
    suspend fun findSearching(): List<HelpRequestRecord> =
        db.query {
            HelpRequestsTable
                .selectAll()
                .where { HelpRequestsTable.status eq RequestStatus.SEARCHING.dbValue }
                .map { it.toRecord() }
        }

    /** Звонки (`accepted`, `in_call`), принятые раньше [time]. */
    suspend fun findCallsAcceptedBefore(time: Instant): List<HelpRequestRecord> =
        db.query {
            HelpRequestsTable
                .selectAll()
                .where { ongoingCall() and (HelpRequestsTable.acceptedAt less time.toDb()) }
                .map { it.toRecord() }
        }

    /** Принятые раньше [time] запросы, в комнату звонка которых ещё никто не вошёл (`accepted`). */
    suspend fun findUnjoinedCallsAcceptedBefore(time: Instant): List<HelpRequestRecord> =
        db.query {
            HelpRequestsTable
                .selectAll()
                .where {
                    (HelpRequestsTable.status eq RequestStatus.ACCEPTED.dbValue) and (HelpRequestsTable.acceptedAt less time.toDb())
                }.map { it.toRecord() }
        }

    /**
     * Меняет статус на [to], только если текущий — один из [from]. Для окончательных статусов
     * запоминает время закрытия. Возвращает обновлённый запрос или `null`, если статус уже другой.
     */
    suspend fun transition(
        id: Uuid,
        from: Set<RequestStatus>,
        to: RequestStatus,
        now: Instant,
    ): HelpRequestRecord? =
        db.query {
            val fromValues = from.map { it.dbValue }
            HelpRequestsTable
                .updateReturning(where = { (HelpRequestsTable.id eq id) and (HelpRequestsTable.status inList fromValues) }) {
                    it[status] = to.dbValue
                    if (!to.isActive) it[endedAt] = now.toDb()
                }.singleOrNull()
                ?.toRecord()
        }

    /**
     * Волонтёр принимает запрос. Побеждает первый: статус меняется, только пока он `searching`.
     * Отмечает результат уведомления: `accepted` у победителя, `too_late` у опоздавшего.
     */
    suspend fun accept(
        requestId: Uuid,
        volunteerId: Uuid,
        now: Instant,
    ): AcceptResult =
        try {
            db.query {
                val notification = notificationOf(requestId, volunteerId) ?: return@query AcceptResult.NotNotified
                val accepted =
                    HelpRequestsTable
                        .updateReturning(
                            where = {
                                (HelpRequestsTable.id eq requestId) and (HelpRequestsTable.status eq RequestStatus.SEARCHING.dbValue)
                            },
                        ) {
                            it[status] = RequestStatus.ACCEPTED.dbValue
                            it[acceptedBy] = volunteerId
                            it[acceptedAt] = now.toDb()
                        }.singleOrNull()
                        ?.toRecord()
                if (accepted == null) {
                    val current = checkNotNull(findOne { HelpRequestsTable.id eq requestId })
                    if (current.acceptedBy != volunteerId && notification[RequestNotificationsTable.result] == null) {
                        setResult(requestId, volunteerId, RESULT_TOO_LATE)
                    }
                    return@query AcceptResult.NotSearching(current)
                }
                setResult(requestId, volunteerId, RESULT_ACCEPTED)
                AcceptResult.Accepted(accepted, waitingVolunteers(requestId))
            }
        } catch (e: ExposedSQLException) {
            // Уникальный индекс «один звонок у волонтёра»: он уже принял другой запрос.
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            AcceptResult.VolunteerBusy
        }

    /** Получал ли волонтёр уведомление о запросе. */
    suspend fun wasNotified(
        requestId: Uuid,
        volunteerId: Uuid,
    ): Boolean = db.query { notificationOf(requestId, volunteerId) != null }

    /** Уведомлённые волонтёры, которые не ответили на запрос: им нужно сообщить, что он закрыт. */
    suspend fun findWaitingVolunteers(requestId: Uuid): List<Uuid> = db.query { waitingVolunteers(requestId) }

    /**
     * Занимает волну номер [wave]: сдвигает `next_wave` с [expectedNextWave] на `wave + 1`,
     * только если поиск ещё идёт. `false` — волну уже отправили или поиск закончен.
     */
    suspend fun claimWave(
        requestId: Uuid,
        expectedNextWave: Int,
        wave: Int,
    ): Boolean =
        db.query {
            HelpRequestsTable.update({
                (HelpRequestsTable.id eq requestId) and
                    (HelpRequestsTable.status eq RequestStatus.SEARCHING.dbValue) and
                    (HelpRequestsTable.nextWave eq expectedNextWave)
            }) {
                it[nextWave] = wave + 1
            } == 1
        }

    /** Сведения о волонтёрах [volunteerIds] для подбора к запросу [requestId]. */
    suspend fun volunteerActivity(
        requestId: Uuid,
        volunteerIds: Collection<Uuid>,
    ): VolunteerActivity {
        if (volunteerIds.isEmpty()) return VolunteerActivity(emptyMap(), emptySet(), emptySet())
        return db.query {
            val lastSent = RequestNotificationsTable.sentAt.max()
            val lastNotifiedAt =
                RequestNotificationsTable
                    .select(RequestNotificationsTable.volunteerId, lastSent)
                    .where { RequestNotificationsTable.volunteerId inList volunteerIds }
                    .groupBy(RequestNotificationsTable.volunteerId)
                    .mapNotNull { row -> row[lastSent]?.let { row[RequestNotificationsTable.volunteerId] to it.toInstant() } }
                    .toMap()
            // Звонок тоже беспокоит: волонтёр, который только что договорил, не должен стоять в очереди первым.
            val lastEnded = HelpRequestsTable.endedAt.max()
            val lastCallEndedAt =
                HelpRequestsTable
                    .select(HelpRequestsTable.acceptedBy, lastEnded)
                    .where { HelpRequestsTable.acceptedBy inList volunteerIds }
                    .groupBy(HelpRequestsTable.acceptedBy)
                    .mapNotNull { row ->
                        val volunteerId = row[HelpRequestsTable.acceptedBy] ?: return@mapNotNull null
                        row[lastEnded]?.let { volunteerId to it.toInstant() }
                    }.toMap()
            val lastDisturbedAt =
                (lastNotifiedAt.keys + lastCallEndedAt.keys).associateWith { id ->
                    listOfNotNull(lastNotifiedAt[id], lastCallEndedAt[id]).max()
                }
            val inCall =
                HelpRequestsTable
                    .select(HelpRequestsTable.acceptedBy)
                    .where { (HelpRequestsTable.acceptedBy inList volunteerIds) and ongoingCall() }
                    .mapNotNull { it[HelpRequestsTable.acceptedBy] }
                    .toSet()
            val notified =
                RequestNotificationsTable
                    .select(RequestNotificationsTable.volunteerId)
                    .where { RequestNotificationsTable.requestId eq requestId }
                    .map { it[RequestNotificationsTable.volunteerId] }
                    .toSet()
            VolunteerActivity(lastDisturbedAt, inCall, notified)
        }
    }

    /** Запоминает, что волонтёрам [volunteerIds] отправлено уведомление о запросе в волне [wave]. */
    suspend fun recordNotifications(
        requestId: Uuid,
        wave: Int,
        volunteerIds: List<Uuid>,
        now: Instant,
    ) {
        if (volunteerIds.isEmpty()) return
        db.query {
            // ignore: волонтёр, уже уведомлённый об этом запросе, пропускается.
            RequestNotificationsTable.batchInsert(volunteerIds, ignore = true, shouldReturnGeneratedValues = false) { volunteerId ->
                this[RequestNotificationsTable.requestId] = requestId
                this[RequestNotificationsTable.volunteerId] = volunteerId
                this[RequestNotificationsTable.wave] = wave
                this[RequestNotificationsTable.sentAt] = now.toDb()
            }
        }
    }

    /** Сохраняет оценку; повторная оценка того же пользователя заменяет прежнюю. */
    suspend fun saveRating(
        requestId: Uuid,
        userId: Uuid,
        helped: Boolean,
        now: Instant,
    ) {
        db.query {
            RatingsTable.upsert {
                it[this.requestId] = requestId
                it[fromUserId] = userId
                it[this.helped] = helped
                it[createdAt] = now.toDb()
            }
        }
    }

    private fun JdbcTransaction.findOne(where: () -> Op<Boolean>): HelpRequestRecord? =
        HelpRequestsTable
            .selectAll()
            .where(where)
            .singleOrNull()
            ?.toRecord()

    private fun JdbcTransaction.notificationOf(
        requestId: Uuid,
        volunteerId: Uuid,
    ): ResultRow? =
        RequestNotificationsTable
            .selectAll()
            .where { (RequestNotificationsTable.requestId eq requestId) and (RequestNotificationsTable.volunteerId eq volunteerId) }
            .singleOrNull()

    private fun JdbcTransaction.setResult(
        requestId: Uuid,
        volunteerId: Uuid,
        result: String,
    ) {
        RequestNotificationsTable.update({
            (RequestNotificationsTable.requestId eq requestId) and (RequestNotificationsTable.volunteerId eq volunteerId)
        }) {
            it[this.result] = result
        }
    }

    private fun JdbcTransaction.waitingVolunteers(requestId: Uuid): List<Uuid> =
        RequestNotificationsTable
            .select(RequestNotificationsTable.volunteerId)
            .where { (RequestNotificationsTable.requestId eq requestId) and RequestNotificationsTable.result.isNull() }
            .map { it[RequestNotificationsTable.volunteerId] }

    private companion object {
        const val RESULT_ACCEPTED = "accepted"
        const val RESULT_TOO_LATE = "too_late"

        val ACTIVE_STATUSES = RequestStatus.entries.filter { it.isActive }.map { it.dbValue }
        val CALL_STATUSES = listOf(RequestStatus.ACCEPTED, RequestStatus.IN_CALL).map { it.dbValue }

        fun activeRequest(): Op<Boolean> = HelpRequestsTable.status inList ACTIVE_STATUSES

        fun ongoingCall(): Op<Boolean> = HelpRequestsTable.status inList CALL_STATUSES
    }
}

private fun ResultRow.toRecord() =
    HelpRequestRecord(
        id = this[HelpRequestsTable.id],
        blindUserId = this[HelpRequestsTable.blindUserId],
        language = languageFromDb(this[HelpRequestsTable.language]),
        genderPreference = genderPreferenceFromDb(this[HelpRequestsTable.genderPreference]),
        status = requestStatusFromDb(this[HelpRequestsTable.status]),
        acceptedBy = this[HelpRequestsTable.acceptedBy],
        nextWave = this[HelpRequestsTable.nextWave],
        createdAt = this[HelpRequestsTable.createdAt].toInstant(),
        acceptedAt = this[HelpRequestsTable.acceptedAt]?.toInstant(),
        endedAt = this[HelpRequestsTable.endedAt]?.toInstant(),
    )
