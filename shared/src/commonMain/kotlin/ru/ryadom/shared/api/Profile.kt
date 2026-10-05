package ru.ryadom.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Роль пользователя. `ADMIN` назначается только вручную на сервере. */
@Serializable
enum class Role {
    @SerialName("blind")
    BLIND,

    @SerialName("volunteer")
    VOLUNTEER,

    @SerialName("admin")
    ADMIN,
}

/** Роль, которую пользователь может выбрать сам в `PATCH /me`. */
@Serializable
enum class SelectableRole(
    val role: Role,
) {
    @SerialName("blind")
    BLIND(Role.BLIND),

    @SerialName("volunteer")
    VOLUNTEER(Role.VOLUNTEER),
}

/** Язык общения (ISO 639-1). */
@Serializable
enum class Language {
    @SerialName("ru")
    RU,

    @SerialName("en")
    EN,
}

/** Пол волонтёра: нужен, только если незрячий попросит помощника определённого пола. */
@Serializable
enum class Gender {
    @SerialName("male")
    MALE,

    @SerialName("female")
    FEMALE,

    @SerialName("unspecified")
    UNSPECIFIED,
}

/** Пожелание незрячего к полу волонтёра. */
@Serializable
enum class GenderPreference {
    @SerialName("any")
    ANY,

    @SerialName("male")
    MALE,

    @SerialName("female")
    FEMALE,
}

/**
 * Окно «не беспокоить» по местному времени пользователя, строки `ЧЧ:ММ`.
 * Может переходить через полночь (22:00–08:00). Если [from] равно [to], окно пустое.
 */
@Serializable
data class DoNotDisturb(
    val from: String,
    val to: String,
) {
    /** Время тишины есть: если начало равно концу, окно пустое и вызовы приходят круглосуточно. */
    val isSet: Boolean get() = from != to

    /**
     * Время [time] (`ЧЧ:ММ`) внутри окна — так же, как считает сервер (`VolunteerMatcher`) и сайт
     * (`web/src/volunteer/quietHours.ts`): начало включительно, конец — нет, окно может переходить через полночь.
     * Строки `ЧЧ:ММ` можно сравнивать как строки.
     */
    fun contains(time: String): Boolean =
        when {
            !isSet -> false
            from < to -> time >= from && time < to
            else -> time >= from || time < to
        }

    companion object {
        val TIME_REGEX = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}

/** Профиль пользователя — ответ `GET /me` и часть [AuthResponse]. */
@Serializable
data class UserProfile(
    val id: String,
    /** `null` — роль ещё не выбрана: клиент должен предложить выбрать её. */
    val role: Role?,
    /** Имя, которое видит собеседник. `null` — ещё не задано. */
    val displayName: String?,
    val languages: List<Language>,
    val gender: Gender,
    val genderPreference: GenderPreference,
    /** Часовой пояс IANA, например `Europe/Moscow`. */
    val timezone: String,
    val doNotDisturb: DoNotDisturb,
    /** Получать ли вызовы о помощи (для волонтёра). */
    val notificationsEnabled: Boolean,
    /** Дата регистрации, ISO 8601 в UTC. */
    val createdAt: String,
) {
    companion object {
        const val DISPLAY_NAME_MAX_LENGTH = 50
    }
}

/** Тело `PATCH /me`. `null` (или отсутствие поля) — не менять. */
@Serializable
data class UpdateProfileRequest(
    val role: SelectableRole? = null,
    val displayName: String? = null,
    val languages: List<Language>? = null,
    val gender: Gender? = null,
    val genderPreference: GenderPreference? = null,
    val timezone: String? = null,
    val doNotDisturb: DoNotDisturb? = null,
    val notificationsEnabled: Boolean? = null,
)
