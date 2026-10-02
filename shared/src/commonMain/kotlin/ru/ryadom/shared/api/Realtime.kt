package ru.ryadom.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Сообщение клиента в WebSocket [ApiPaths.REALTIME] (схема `RealtimeClientMessage`).
 * В JSON вид сообщения — поле `type`.
 */
@Serializable
sealed interface ClientMessage {
    /**
     * Вход: первое сообщение после открытия соединения. После каждого обновления токенов
     * клиент отправляет его снова с новым токеном, иначе сервер закроет соединение, когда старый истечёт.
     */
    @Serializable
    @SerialName("auth")
    data class Auth(
        val accessToken: String,
    ) : ClientMessage
}

/**
 * Сообщение сервера в WebSocket [ApiPaths.REALTIME] (схема `RealtimeServerMessage`).
 * В JSON вид сообщения — поле `type`, например `{"type":"request.accepted","request":{...}}`.
 */
@Serializable
sealed interface ServerEvent {
    /** Вход выполнен, соединение готово получать события. */
    @Serializable
    @SerialName("ready")
    data object Ready : ServerEvent

    /** Изменился запрос помощи; [request] — его актуальное состояние. */
    @Serializable
    sealed interface RequestEvent : ServerEvent {
        val request: HelpRequest
    }

    /** Волонтёру: новый запрос, его можно принять. */
    @Serializable
    @SerialName("request.incoming")
    data class RequestIncoming(
        override val request: HelpRequest,
    ) : RequestEvent

    /** Незрячему: волонтёр принял запрос, в `request.call` — данные для звонка. */
    @Serializable
    @SerialName("request.accepted")
    data class RequestAccepted(
        override val request: HelpRequest,
    ) : RequestEvent

    /** Волонтёру: запрос уже принял другой волонтёр. */
    @Serializable
    @SerialName("request.taken")
    data class RequestTaken(
        override val request: HelpRequest,
    ) : RequestEvent

    /** Волонтёру: незрячий отменил запрос. */
    @Serializable
    @SerialName("request.cancelled")
    data class RequestCancelled(
        override val request: HelpRequest,
    ) : RequestEvent

    /** Незрячему и уведомлённым волонтёрам: за время поиска никто не принял запрос. */
    @Serializable
    @SerialName("request.no_answer")
    data class RequestNoAnswer(
        override val request: HelpRequest,
    ) : RequestEvent

    /** Участникам звонка: звонок завершён. */
    @Serializable
    @SerialName("request.ended")
    data class RequestEnded(
        override val request: HelpRequest,
    ) : RequestEvent
}

/** Общие правила WebSocket [ApiPaths.REALTIME] для сервера и клиентов. */
object Realtime {
    /** Сервер закрывает соединение с этим кодом, если нет входа или токен неверный либо истёк: обновите токены и подключитесь снова. */
    const val CLOSE_UNAUTHORIZED: Short = 4401

    /** Сервер закрывает соединение с этим кодом, если пользователь заблокирован. */
    const val CLOSE_BANNED: Short = 4403

    /** JSON сообщений WebSocket. Неизвестные поля игнорируются: новая версия сервера может их добавить. */
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    fun encode(event: ServerEvent): String = json.encodeToString(ServerEvent.serializer(), event)

    /** Разбирает сообщение сервера. `null` — неизвестный тип или повреждённый JSON: такое сообщение клиент пропускает. */
    fun decodeServerEvent(text: String): ServerEvent? =
        try {
            json.decodeFromString(ServerEvent.serializer(), text)
        } catch (e: SerializationException) {
            null
        }
}
