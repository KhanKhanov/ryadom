package ru.ryadom.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Статус запроса помощи (схема `RequestStatus` в `docs/api/openapi.yaml`). */
@Serializable
enum class RequestStatus {
    /** Ищем волонтёра. */
    @SerialName("searching")
    SEARCHING,

    /** Волонтёр принял запрос, участники подключаются к звонку. */
    @SerialName("accepted")
    ACCEPTED,

    /** Звонок идёт. */
    @SerialName("in_call")
    IN_CALL,

    /** Звонок завершён. */
    @SerialName("ended")
    ENDED,

    /** За время поиска никто не принял запрос. */
    @SerialName("no_answer")
    NO_ANSWER,

    /** Незрячий отменил запрос до начала звонка. */
    @SerialName("cancelled")
    CANCELLED,
    ;

    /** Запрос ещё не закрыт: идёт поиск или звонок. У незрячего может быть только один такой запрос. */
    val isActive: Boolean get() = this == SEARCHING || this == ACCEPTED || this == IN_CALL
}

/** Тело `POST /requests`. `null` — взять значение из профиля (первый язык, пожелание по полу). */
@Serializable
data class CreateHelpRequest(
    val language: Language? = null,
    val genderPreference: GenderPreference? = null,
)

/** Запрос помощи — ответ методов `/requests/...` и содержимое событий WebSocket. */
@Serializable
data class HelpRequest(
    val id: String,
    val status: RequestStatus,
    val language: Language,
    val genderPreference: GenderPreference,
    /** ISO 8601 в UTC, как и остальные даты. */
    val createdAt: String,
    /** Когда волонтёр принял запрос; `null` — ещё не принят. */
    val acceptedAt: String?,
    /** Когда запрос закрыт; `null` — ещё активен. */
    val endedAt: String?,
    /** Данные для входа в звонок — только для его участников, пока статус [RequestStatus.ACCEPTED] или [RequestStatus.IN_CALL]. */
    val call: CallCredentials?,
)

/** Данные для подключения SDK LiveKit к комнате звонка. */
@Serializable
data class CallCredentials(
    /** Адрес сервера LiveKit (`ws://` или `wss://`). */
    val url: String,
    /** Имя комнаты — совпадает с id запроса. */
    val room: String,
    /** Токен LiveKit для этой комнаты и этого участника. */
    val token: String,
)

/** Тело `POST /requests/{requestId}/rating`: помог ли звонок. */
@Serializable
data class Rating(
    val helped: Boolean,
)
