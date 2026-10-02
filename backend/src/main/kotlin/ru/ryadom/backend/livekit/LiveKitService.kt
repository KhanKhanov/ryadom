package ru.ryadom.backend.livekit

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishData
import io.livekit.server.CanPublishSources
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import io.livekit.server.WebhookReceiver
import org.slf4j.LoggerFactory
import ru.ryadom.backend.LiveKitConfig
import ru.ryadom.shared.api.CallCredentials
import java.time.Clock
import java.util.Date
import kotlin.uuid.Uuid

/** Роль участника звонка: от неё зависит, что он может публиковать. */
enum class CallRole(
    /** Источники, которые участник может публиковать (названия LiveKit). */
    val publishSources: List<String>,
) {
    /** Незрячий показывает задней камерой и говорит. */
    BLIND(listOf("camera", "microphone")),

    /** Волонтёр только говорит: его камера не нужна. */
    VOLUNTEER(listOf("microphone")),
}

/** Событие из webhook LiveKit — только то, что нужно серверу. */
data class LiveKitEvent(
    /** Тип события LiveKit, например [ROOM_FINISHED]. */
    val type: String,
    val roomName: String?,
) {
    companion object {
        const val PARTICIPANT_JOINED = "participant_joined"
        const val ROOM_FINISHED = "room_finished"
    }
}

/**
 * Связь с LiveKit без сетевых запросов: выпуск токенов для входа в комнату и проверка подписи webhook.
 * Комната звонка называется по id запроса и создаётся самим LiveKit, когда в неё входит первый участник.
 */
class LiveKitService(
    private val config: LiveKitConfig,
    private val clock: Clock,
) {
    private val webhookReceiver = WebhookReceiver(config.apiKey, config.apiSecret)

    /** Данные для входа участника [userId] в комнату звонка по запросу [requestId]. */
    fun credentials(
        requestId: Uuid,
        userId: Uuid,
        displayName: String?,
        role: CallRole,
    ): CallCredentials {
        val room = requestId.toString()
        val token =
            AccessToken(config.apiKey, config.apiSecret).apply {
                identity = userId.toString()
                // Имя видит собеседник (например, волонтёр слышит, как зовут незрячего).
                name = displayName
                expiration = Date.from(clock.instant().plus(config.tokenTtl))
                addGrants(
                    RoomJoin(true),
                    RoomName(room),
                    CanSubscribe(true),
                    CanPublish(true),
                    CanPublishSources(role.publishSources),
                    // Данные — команды фонарика от волонтёра и ответы приложения незрячего.
                    CanPublishData(true),
                )
            }
        return CallCredentials(url = config.url, room = room, token = token.toJwt())
    }

    /**
     * Проверяет подпись webhook ([authorization] — заголовок `Authorization`) и разбирает событие.
     * `null` — подпись неверна или тело повреждено.
     */
    fun parseWebhook(
        body: String,
        authorization: String?,
    ): LiveKitEvent? =
        try {
            val event = webhookReceiver.receive(body, authorization)
            LiveKitEvent(type = event.event, roomName = event.room.name.takeIf { event.hasRoom() })
        } catch (e: Exception) {
            // Подпись, срок действия, SHA-256 тела или формат JSON — подробности не нужны и могут содержать данные запроса.
            log.warn("Rejected LiveKit webhook: {}", e::class.java.simpleName)
            null
        }

    private companion object {
        val log = LoggerFactory.getLogger(LiveKitService::class.java)
    }
}
