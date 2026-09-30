package ru.ryadom.backend.users

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.db.AuthIdentitiesTable
import ru.ryadom.backend.db.UsersTable
import ru.ryadom.backend.db.toDb
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.Role
import java.time.Instant
import java.time.LocalTime
import kotlin.uuid.Uuid

/** Способ входа. [dbValue] хранится в `auth_identities.provider`. */
enum class IdentityProvider(
    val dbValue: String,
) {
    DEV("dev"),
    YANDEX("yandex"),
}

/** Пользователь, как он хранится в базе. */
data class UserRecord(
    val id: Uuid,
    val role: Role?,
    val displayName: String?,
    val languages: List<Language>,
    val gender: Gender,
    val genderPreference: GenderPreference,
    val timezone: String,
    /** `null` — окно «не беспокоить» по умолчанию из конфига. */
    val doNotDisturb: Pair<LocalTime, LocalTime>?,
    val notificationsEnabled: Boolean,
    val bannedAt: Instant?,
    val createdAt: Instant,
) {
    val isBanned: Boolean get() = bannedAt != null
}

/** Данные для создания пользователя при первом входе. */
data class NewUser(
    val displayName: String?,
    val language: Language,
    val timezone: String,
)

/** Изменения профиля; `null` — поле не меняется. */
data class ProfileChanges(
    val role: Role? = null,
    val displayName: String? = null,
    val languages: List<Language>? = null,
    val gender: Gender? = null,
    val genderPreference: GenderPreference? = null,
    val timezone: String? = null,
    val doNotDisturb: Pair<LocalTime, LocalTime>? = null,
    val notificationsEnabled: Boolean? = null,
)

class UserRepository(
    private val db: AppDatabase,
) {
    suspend fun findById(id: Uuid): UserRecord? = db.query { findUser(id) }

    /**
     * Находит пользователя по способу входа или создаёт нового.
     * Безопасно при одновременных запросах с одним [subject] (например, двойное нажатие «Войти»):
     * создастся ровно один пользователь.
     * @return пользователь и `true`, если он только что создан.
     */
    suspend fun findOrCreateByIdentity(
        provider: IdentityProvider,
        subject: String,
        newUser: NewUser,
        now: Instant,
    ): Pair<UserRecord, Boolean> {
        db.query { findUserByIdentity(provider, subject) }?.let { return it to false }
        return try {
            db.query {
                val userId = Uuid.random()
                UsersTable.insert {
                    it[id] = userId
                    it[displayName] = newUser.displayName
                    it[languages] = listOf(newUser.language.dbValue)
                    it[timezone] = newUser.timezone
                    it[notificationsEnabled] = true
                    it[createdAt] = now.toDb()
                }
                AuthIdentitiesTable.insert {
                    it[id] = Uuid.random()
                    it[this.userId] = userId
                    it[this.provider] = provider.dbValue
                    it[this.subject] = subject
                    it[createdAt] = now.toDb()
                }
                checkNotNull(findUser(userId)) to true
            }
        } catch (e: ExposedSQLException) {
            // Параллельный запрос успел создать этого пользователя раньше — берём его.
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            checkNotNull(db.query { findUserByIdentity(provider, subject) }) to false
        }
    }

    /** Применяет [changes] и возвращает обновлённого пользователя (`null`, если его нет). */
    suspend fun update(
        id: Uuid,
        changes: ProfileChanges,
    ): UserRecord? =
        db.query {
            if (changes != ProfileChanges()) {
                UsersTable.update({ UsersTable.id eq id }) {
                    changes.role?.let { role -> it[this.role] = role.dbValue }
                    changes.displayName?.let { name -> it[displayName] = name }
                    changes.languages?.let { list -> it[languages] = list.map { language -> language.dbValue } }
                    changes.gender?.let { gender -> it[this.gender] = gender.toDb() }
                    changes.genderPreference?.let { preference -> it[genderPreference] = preference.toDb() }
                    changes.timezone?.let { zone -> it[timezone] = zone }
                    changes.doNotDisturb?.let { (from, to) ->
                        it[dndFrom] = from
                        it[dndTo] = to
                    }
                    changes.notificationsEnabled?.let { enabled -> it[notificationsEnabled] = enabled }
                }
            }
            findUser(id)
        }

    private fun JdbcTransaction.findUser(id: Uuid): UserRecord? =
        UsersTable
            .selectAll()
            .where { UsersTable.id eq id }
            .singleOrNull()
            ?.toUserRecord()

    private fun JdbcTransaction.findUserByIdentity(
        provider: IdentityProvider,
        subject: String,
    ): UserRecord? {
        val userId =
            AuthIdentitiesTable
                .select(AuthIdentitiesTable.userId)
                .where { (AuthIdentitiesTable.provider eq provider.dbValue) and (AuthIdentitiesTable.subject eq subject) }
                .singleOrNull()
                ?.get(AuthIdentitiesTable.userId)
                ?: return null
        return findUser(userId)
    }

    private companion object {
        /** Код ошибки PostgreSQL «нарушение уникальности». */
        const val UNIQUE_VIOLATION = "23505"
    }
}

// Перевод значений между Kotlin и базой. В базе — те же строки, что в API (`blind`, `ru`, `male`),
// а «не указано» хранится как NULL.

private val Role.dbValue: String get() = name.lowercase()
private val Language.dbValue: String get() = name.lowercase()

private fun Gender.toDb(): String? = if (this == Gender.UNSPECIFIED) null else name.lowercase()

private fun GenderPreference.toDb(): String? = if (this == GenderPreference.ANY) null else name.lowercase()

private fun ResultRow.toUserRecord(): UserRecord {
    val from = this[UsersTable.dndFrom]
    val to = this[UsersTable.dndTo]
    return UserRecord(
        id = this[UsersTable.id],
        role = this[UsersTable.role]?.let { Role.valueOf(it.uppercase()) },
        displayName = this[UsersTable.displayName],
        languages = this[UsersTable.languages].map { Language.valueOf(it.uppercase()) },
        gender = this[UsersTable.gender]?.let { Gender.valueOf(it.uppercase()) } ?: Gender.UNSPECIFIED,
        genderPreference =
            this[UsersTable.genderPreference]?.let { GenderPreference.valueOf(it.uppercase()) } ?: GenderPreference.ANY,
        timezone = this[UsersTable.timezone],
        doNotDisturb = if (from != null && to != null) from to to else null,
        notificationsEnabled = this[UsersTable.notificationsEnabled],
        bannedAt = this[UsersTable.bannedAt]?.toInstant(),
        createdAt = this[UsersTable.createdAt].toInstant(),
    )
}
