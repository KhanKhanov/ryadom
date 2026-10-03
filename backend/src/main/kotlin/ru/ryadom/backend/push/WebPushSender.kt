package ru.ryadom.backend.push

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import ru.ryadom.backend.WebPushSettings
import ru.ryadom.shared.api.PushMessage
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * Web Push (RFC 8030): уведомление шифруется для браузера ([WebPushCrypto]), подписывается ключом
 * сервера ([Vapid]) и отправляется на адрес подписки — push-сервис браузера (Google, Mozilla, Apple…)
 * доставит его, даже когда сайт закрыт.
 *
 * @param http клиент без перехода по редиректам: адрес подписки прислал браузер, и сервер не должен
 *   уходить с проверенного адреса push-сервиса куда-то ещё.
 */
class WebPushSender(
    settings: WebPushSettings,
    private val http: HttpClient,
    clock: Clock,
) : PushSender {
    private val vapid = Vapid(VapidKeys.parse(settings.publicKey, settings.privateKey), settings.subject, clock)

    override suspend fun send(
        device: DeviceRecord,
        message: PushMessage,
        ttl: Duration,
    ): PushResult {
        val keys = device.webPush ?: return PushResult.DEVICE_GONE
        val payload = JsonObject(message.toData().mapValues { JsonPrimitive(it.value) }).toString().toByteArray()
        val body = WebPushCrypto.encrypt(payload, Base64Url.decode(keys.p256dh), Base64Url.decode(keys.auth))
        val response =
            try {
                http.post(device.token) {
                    header(HttpHeaders.Authorization, vapid.authorization(URI(device.token)))
                    header(HttpHeaders.ContentEncoding, "aes128gcm")
                    header("TTL", ttl.seconds.coerceAtLeast(0))
                    // Вызов срочный: push-сервис доставит его сразу, даже телефону в режиме экономии.
                    header("Urgency", "high")
                    setBody(ByteArrayContent(body, ContentType.Application.OctetStream))
                }
            } catch (e: IOException) {
                log.warn("Web Push to device {} failed: {}", device.id, e::class.java.simpleName)
                return PushResult.FAILED
            }
        return when (response.status.value) {
            in 200..299 -> {
                PushResult.SENT
            }

            // Подписки больше нет: браузер отписался или удалил данные сайта (RFC 8030, раздел 7.3).
            404, 410 -> {
                PushResult.DEVICE_GONE
            }

            else -> {
                log.warn("Web Push to device {} failed: HTTP {}", device.id, response.status.value)
                PushResult.FAILED
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(WebPushSender::class.java)
    }
}
