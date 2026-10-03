package ru.ryadom.backend.push

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import ru.ryadom.backend.RuStoreSettings
import ru.ryadom.shared.api.PushMessage
import java.io.IOException
import java.time.Duration

/**
 * RuStore Push (VK): push-уведомления на Android-телефоны без сервисов Google.
 * Формат запроса повторяет FCM HTTP v1 (https://www.rustore.ru/help/sdk/push-notifications/send-push-notifications),
 * вход — сервисным токеном проекта из консоли RuStore. Уведомление — только данные (`data`), как и в FCM.
 */
class RuStoreSender(
    private val settings: RuStoreSettings,
    private val http: HttpClient,
    private val apiUrl: String = "https://vkpns.rustore.ru",
) : PushSender {
    override suspend fun send(
        device: DeviceRecord,
        message: PushMessage,
        ttl: Duration,
    ): PushResult {
        val body =
            buildJsonObject {
                put(
                    "message",
                    buildJsonObject {
                        put("token", device.token)
                        put("data", JsonObject(message.toData().mapValues { JsonPrimitive(it.value) }))
                        put("android", buildJsonObject { put("ttl", "${ttl.seconds.coerceAtLeast(0)}s") })
                    },
                )
            }.toString()
        val response =
            try {
                http.post("$apiUrl/v1/projects/${settings.projectId}/messages:send") {
                    bearerAuth(settings.serviceToken)
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            } catch (e: IOException) {
                log.warn("RuStore Push to device {} failed: {}", device.id, e::class.java.simpleName)
                return PushResult.FAILED
            }
        if (response.status.value in 200..299) return PushResult.SENT
        val error = fcmErrorCode(response.bodyAsText())
        // Устройство больше не получает уведомления: приложение удалено или токен устарел.
        if (response.status.value == HTTP_NOT_FOUND || error in GONE_ERRORS) return PushResult.DEVICE_GONE
        log.warn("RuStore Push to device {} failed: HTTP {} {}", device.id, response.status.value, error)
        return PushResult.FAILED
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        val GONE_ERRORS = setOf("UNREGISTERED", "NOT_FOUND")
        val log = LoggerFactory.getLogger(RuStoreSender::class.java)
    }
}
