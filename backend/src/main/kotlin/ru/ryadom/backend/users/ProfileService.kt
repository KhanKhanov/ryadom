package ru.ryadom.backend.users

import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.DoNotDisturb
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid

/** Профиль текущего пользователя: чтение, проверка и сохранение изменений. */
class ProfileService(
    private val users: UserRepository,
    private val defaults: ProfileDefaults,
) {
    suspend fun get(userId: Uuid): UserProfile = toProfile(activeUser(userId))

    suspend fun update(
        userId: Uuid,
        request: UpdateProfileRequest,
    ): UserProfile {
        val changes = request.toChanges(activeUser(userId))
        val updated = users.update(userId, changes) ?: throw unauthorized()
        return toProfile(updated)
    }

    fun toProfile(user: UserRecord): UserProfile {
        val (from, to) = user.doNotDisturb ?: (defaults.doNotDisturbFrom to defaults.doNotDisturbTo)
        return UserProfile(
            id = user.id.toString(),
            role = user.role,
            displayName = user.displayName,
            languages = user.languages,
            gender = user.gender,
            genderPreference = user.genderPreference,
            timezone = user.timezone,
            doNotDisturb = DoNotDisturb(from.format(TIME_FORMAT), to.format(TIME_FORMAT)),
            notificationsEnabled = user.notificationsEnabled,
            createdAt = DateTimeFormatter.ISO_INSTANT.format(user.createdAt.truncatedTo(ChronoUnit.SECONDS)),
        )
    }

    /** Пользователь из токена. Если его уже нет в базе — токен считается недействительным. */
    private suspend fun activeUser(userId: Uuid): UserRecord {
        val user = users.findById(userId) ?: throw unauthorized()
        if (user.isBanned) throw ApiException.userBanned()
        return user
    }

    private fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED, "User not found")

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** Проверяет запрос и переводит его в изменения для базы. Бросает [ApiException] с понятной причиной. */
internal fun UpdateProfileRequest.toChanges(current: UserRecord): ProfileChanges {
    if (role != null && current.role == Role.ADMIN) {
        throw ApiException(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN, "Admin role cannot be changed via profile")
    }
    val name =
        displayName?.let {
            normalizeDisplayName(it)
                ?: throw ApiException.invalidRequest(
                    "displayName must be 1..${UserProfile.DISPLAY_NAME_MAX_LENGTH} characters without control characters",
                )
        }
    val languageList = languages
    if (languageList != null && languageList.isEmpty()) {
        throw ApiException.invalidRequest("languages must not be empty")
    }
    if (timezone != null && timezone !in ZoneId.getAvailableZoneIds()) {
        throw ApiException.invalidRequest("timezone must be an IANA time zone, e.g. Europe/Moscow")
    }
    val dnd =
        doNotDisturb?.let {
            if (!DoNotDisturb.TIME_REGEX.matches(it.from) || !DoNotDisturb.TIME_REGEX.matches(it.to)) {
                throw ApiException.invalidRequest("doNotDisturb.from and doNotDisturb.to must be HH:mm")
            }
            LocalTime.parse(it.from) to LocalTime.parse(it.to)
        }
    return ProfileChanges(
        role = role?.role,
        displayName = name,
        languages = languageList?.distinct(),
        gender = gender,
        genderPreference = genderPreference,
        timezone = timezone,
        doNotDisturb = dnd,
        notificationsEnabled = notificationsEnabled,
    )
}

/**
 * Приводит имя к виду для хранения: обрезает пробелы по краям.
 * `null` — имя пустое, слишком длинное или содержит управляющие символы.
 */
internal fun normalizeDisplayName(raw: String): String? {
    val name = raw.trim()
    val valid =
        name.isNotEmpty() &&
            name.length <= UserProfile.DISPLAY_NAME_MAX_LENGTH &&
            name.none { it.isISOControl() || it == ' ' || it == ' ' }
    return name.takeIf { valid }
}
