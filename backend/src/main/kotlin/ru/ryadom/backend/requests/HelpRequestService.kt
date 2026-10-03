package ru.ryadom.backend.requests

import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import ru.ryadom.backend.RequestsConfig
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.backend.livekit.CallRole
import ru.ryadom.backend.livekit.LiveKitEvent
import ru.ryadom.backend.livekit.LiveKitService
import ru.ryadom.backend.realtime.RealtimeHub
import ru.ryadom.backend.toApiTime
import ru.ryadom.backend.users.UserRecord
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.backend.users.requireActiveUser
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.CreateHelpRequest
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.IncomingHelpRequests
import ru.ryadom.shared.api.Rating
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.ServerEvent
import java.time.Clock
import kotlin.uuid.Uuid

/** Статусы, в которых идёт звонок (или участники в него подключаются). */
internal val CALL_STATUSES = setOf(RequestStatus.ACCEPTED, RequestStatus.IN_CALL)

/**
 * Запросы помощи: действия пользователей (`/requests/...`) и события комнат LiveKit.
 * Поиск волонтёра и волны уведомлений — в [RequestDispatcher].
 */
class HelpRequestService(
    private val requests: HelpRequestRepository,
    private val users: UserRepository,
    private val dispatcher: RequestDispatcher,
    private val hub: RealtimeHub,
    private val pushes: RequestPushes,
    private val liveKit: LiveKitService,
    private val locks: RequestLocks,
    private val config: RequestsConfig,
    private val clock: Clock,
) {
    /** Незрячий просит помощи: запрос создаётся, и сразу уходит первая волна уведомлений. */
    suspend fun create(
        userId: Uuid,
        body: CreateHelpRequest,
    ): HelpRequest {
        val user = users.requireActiveUser(userId)
        if (user.role != Role.BLIND) throw ApiException.forbidden("Only users with role blind can request help")
        val result =
            requests.create(
                blindUserId = user.id,
                language = body.language ?: user.languages.first(),
                genderPreference = body.genderPreference ?: user.genderPreference,
                now = clock.instant(),
                maxRequests = config.maxRequestsPerWindow,
                window = config.rateLimitWindow,
            )
        val request =
            when (result) {
                is CreateResult.Created -> {
                    result.request
                }

                CreateResult.ActiveRequestExists -> {
                    throw ApiException.conflict(ApiErrorCodes.ACTIVE_REQUEST_EXISTS, "User already has an active help request")
                }

                CreateResult.RateLimited -> {
                    throw ApiException(HttpStatusCode.TooManyRequests, ApiErrorCodes.TOO_MANY_REQUESTS, "Too many help requests, try later")
                }
            }
        log.info("Request {} created by {}", request.id, user.id)
        dispatcher.startSearch(request)
        return request.toApi()
    }

    /** Активный запрос пользователя: свой запрос незрячего или принятый звонок волонтёра. */
    suspend fun current(userId: Uuid): HelpRequest? {
        val user = users.requireActiveUser(userId)
        val request = requests.findActiveByBlind(user.id) ?: requests.findActiveByVolunteer(user.id) ?: return null
        return toApiFor(user, request)
    }

    /** Вызовы, которые ждут ответа волонтёра: идёт поиск, вызов приходил, волонтёр ещё не отвечал. */
    suspend fun incoming(userId: Uuid): IncomingHelpRequests {
        val user = users.requireActiveUser(userId)
        if (user.role != Role.VOLUNTEER) return IncomingHelpRequests(emptyList())
        return IncomingHelpRequests(requests.findIncoming(user.id).map { it.toApi() })
    }

    suspend fun get(
        userId: Uuid,
        requestId: Uuid,
    ): HelpRequest {
        val user = users.requireActiveUser(userId)
        val request = requests.findById(requestId) ?: throw notFound()
        if (!request.isParticipant(user.id) && !requests.wasNotified(request.id, user.id)) throw notFound()
        return toApiFor(user, request)
    }

    /**
     * Участник закрывает запрос: незрячий отменяет поиск или завершает звонок,
     * принявший запрос волонтёр завершает звонок. Повторный вызов ничего не меняет.
     */
    suspend fun cancel(
        userId: Uuid,
        requestId: Uuid,
    ): HelpRequest {
        val user = users.requireActiveUser(userId)
        return locks.withLock(requestId) {
            val request = requests.findById(requestId) ?: throw notFound()
            when (user.id) {
                request.blindUserId -> {
                    cancelByRequester(request)
                }

                request.acceptedBy -> {
                    endByVolunteer(request)
                }

                // Волонтёр, который видит запрос, получает понятную ошибку; остальные — 404, как будто запроса нет.
                else -> {
                    if (requests.wasNotified(request.id, user.id)) {
                        throw ApiException.forbidden("Only participants can cancel a help request or end its call")
                    }
                    throw notFound()
                }
            }
        }
    }

    /** Незрячий отменяет поиск или завершает звонок. Вызывать под блокировкой запроса. */
    private suspend fun cancelByRequester(request: HelpRequestRecord): HelpRequest {
        val closedStatus =
            when (request.status) {
                RequestStatus.SEARCHING, RequestStatus.ACCEPTED -> RequestStatus.CANCELLED
                RequestStatus.IN_CALL -> RequestStatus.ENDED
                else -> return request.toApi() // Уже закрыт — ничего не меняем.
            }
        val closed =
            requests.transition(request.id, from = setOf(request.status), to = closedStatus, clock.instant())
                ?: return checkNotNull(requests.findById(request.id)).toApi()
        log.info("Request {}: {} by requester", closed.id, closed.status.name.lowercase())
        when (request.status) {
            RequestStatus.SEARCHING -> {
                val waiting = requests.findWaitingVolunteers(closed.id)
                hub.send(waiting, ServerEvent.RequestCancelled(closed.toApi()))
                pushes.closed(waiting, closed)
            }

            RequestStatus.ACCEPTED -> {
                hub.send(listOfNotNull(closed.acceptedBy), ServerEvent.RequestCancelled(closed.toApi()))
            }

            else -> {
                hub.sendCallEnded(closed)
            }
        }
        return closed.toApi()
    }

    /**
     * Волонтёр завершает звонок — в том числе до того, как незрячий подключился.
     * Без этого звонок, из которого ушёл только волонтёр, оставался бы открытым, пока не выйдет незрячий,
     * и волонтёр не мог бы принимать новые вызовы. Вызывать под блокировкой запроса.
     */
    private suspend fun endByVolunteer(request: HelpRequestRecord): HelpRequest {
        if (request.status !in CALL_STATUSES) return request.toApi() // Уже закрыт — ничего не меняем.
        val ended =
            requests.transition(request.id, from = CALL_STATUSES, to = RequestStatus.ENDED, clock.instant())
                ?: return checkNotNull(requests.findById(request.id)).toApi()
        log.info("Request {}: call ended by volunteer", ended.id)
        hub.sendCallEnded(ended)
        return ended.toApi()
    }

    /** Волонтёр принимает запрос. Побеждает первый; повторное нажатие того же волонтёра безопасно. */
    suspend fun accept(
        userId: Uuid,
        requestId: Uuid,
    ): HelpRequest {
        val user = users.requireActiveUser(userId)
        if (user.role != Role.VOLUNTEER) throw ApiException.forbidden("Only volunteers can accept help requests")
        return locks.withLock(requestId) {
            when (val result = requests.accept(requestId, user.id, clock.instant())) {
                AcceptResult.NotNotified -> {
                    throw notFound()
                }

                AcceptResult.VolunteerBusy -> {
                    throw ApiException.conflict(ApiErrorCodes.ACTIVE_REQUEST_EXISTS, "Volunteer is already in another call")
                }

                is AcceptResult.NotSearching -> {
                    val request = result.request
                    when {
                        request.acceptedBy == user.id && request.status in CALL_STATUSES -> toApiFor(user, request)
                        request.acceptedBy != null && request.acceptedBy != user.id -> throw requestTaken()
                        else -> throw ApiException.conflict(ApiErrorCodes.REQUEST_CLOSED, "Help request is closed")
                    }
                }

                is AcceptResult.Accepted -> {
                    val request = result.request
                    log.info("Request {} accepted by {}", request.id, user.id)
                    val blind = users.findById(request.blindUserId)
                    val blindCall = liveKit.credentials(request.id, request.blindUserId, blind?.displayName, CallRole.BLIND)
                    hub.send(listOf(request.blindUserId), ServerEvent.RequestAccepted(request.toApi(blindCall)))
                    hub.send(result.otherWaiting, ServerEvent.RequestTaken(request.toApi()))
                    // Вызов звонил и в других вкладках и на других устройствах принявшего — там его пора убрать.
                    // Данных для звонка в событии нет: в звонок входит то соединение, которое принимало вызов.
                    hub.send(listOf(user.id), ServerEvent.RequestAccepted(request.toApi()))
                    // То же — уведомлениям на устройствах: и остальных волонтёров, и самого принявшего.
                    pushes.closed(result.otherWaiting + user.id, request)
                    toApiFor(user, request)
                }
            }
        }
    }

    /** Участник звонка оценивает, помог ли он. Повторная оценка заменяет прежнюю. */
    suspend fun rate(
        userId: Uuid,
        requestId: Uuid,
        rating: Rating,
    ) {
        val user = users.requireActiveUser(userId)
        val request = requests.findById(requestId)?.takeIf { it.isParticipant(user.id) } ?: throw notFound()
        if (request.acceptedBy == null) throw ApiException.conflict(ApiErrorCodes.CALL_NOT_STARTED, "Nobody accepted this help request")
        requests.saveRating(request.id, user.id, rating.helped, clock.instant())
    }

    /** События комнаты звонка от LiveKit: комната называется по id запроса. */
    suspend fun onLiveKitEvent(event: LiveKitEvent) {
        val requestId = event.roomName?.let { Uuid.parseOrNull(it) } ?: return
        when (event.type) {
            LiveKitEvent.PARTICIPANT_JOINED -> {
                locks.withLock(requestId) {
                    val started =
                        requests.transition(
                            requestId,
                            from = setOf(RequestStatus.ACCEPTED),
                            to = RequestStatus.IN_CALL,
                            clock.instant(),
                        )
                    if (started != null) log.info("Request {}: call started", started.id)
                }
            }

            LiveKitEvent.ROOM_FINISHED -> {
                locks.withLock(requestId) {
                    val ended = requests.transition(requestId, from = CALL_STATUSES, to = RequestStatus.ENDED, clock.instant())
                    if (ended != null) {
                        log.info("Request {}: call ended", ended.id)
                        hub.sendCallEnded(ended)
                    }
                }
            }
        }
    }

    /** Модель API для [viewer]: участникам идущего звонка — вместе с данными для входа в него. */
    private fun toApiFor(
        viewer: UserRecord,
        request: HelpRequestRecord,
    ): HelpRequest {
        val role =
            when (viewer.id) {
                request.blindUserId -> CallRole.BLIND
                request.acceptedBy -> CallRole.VOLUNTEER
                else -> null
            }
        val call =
            role
                ?.takeIf { request.status in CALL_STATUSES }
                ?.let { liveKit.credentials(request.id, viewer.id, viewer.displayName, it) }
        return request.toApi(call)
    }

    private fun notFound() = ApiException.notFound("Help request not found")

    private fun requestTaken() = ApiException.conflict(ApiErrorCodes.REQUEST_TAKEN, "Help request was accepted by another volunteer")

    private companion object {
        val log = LoggerFactory.getLogger(HelpRequestService::class.java)
    }
}

/** Запрос в виде модели API. [call] — данные для входа в звонок, только для его участников. */
internal fun HelpRequestRecord.toApi(call: CallCredentials? = null) =
    HelpRequest(
        id = id.toString(),
        status = status,
        language = language,
        genderPreference = genderPreference,
        createdAt = createdAt.toApiTime(),
        acceptedAt = acceptedAt?.toApiTime(),
        endedAt = endedAt?.toApiTime(),
        call = call,
    )

/** Сообщает обоим участникам, что звонок завершён. */
internal fun RealtimeHub.sendCallEnded(request: HelpRequestRecord) =
    send(listOfNotNull(request.blindUserId, request.acceptedBy), ServerEvent.RequestEnded(request.toApi()))
