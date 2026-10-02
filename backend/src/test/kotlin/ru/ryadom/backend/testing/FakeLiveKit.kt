package ru.ryadom.backend.testing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import ru.ryadom.backend.LiveKitConfig
import ru.ryadom.shared.api.ApiPaths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Настройки и подпись webhook, как у настоящего сервера LiveKit, — без самого сервера. */
object FakeLiveKit {
    val config =
        LiveKitConfig(
            url = "ws://livekit.test:7880",
            apiKey = "test-livekit-key",
            apiSecret = "test-livekit-secret-at-least-32-characters",
            tokenTtl = Duration.ofHours(2),
        )

    /**
     * Заголовок `Authorization`, который LiveKit добавляет к webhook: JWT, подписанный секретом API,
     * с SHA-256 тела. Срок — по настоящим часам: так его проверяет SDK LiveKit.
     */
    fun signature(
        body: String,
        secret: String = config.apiSecret,
    ): String {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))
        return JWT
            .create()
            .withIssuer(config.apiKey)
            .withExpiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .withClaim("sha256", hash)
            .sign(Algorithm.HMAC256(secret))
    }

    /** Тело события в формате LiveKit (protobuf JSON). */
    fun event(
        type: String,
        room: String,
        participantIdentity: String? = null,
    ): String {
        val participant = participantIdentity?.let { ""","participant":{"sid":"PA_test","identity":"$it","name":"Анна"}""" }.orEmpty()
        return """{"event":"$type","room":{"sid":"RM_test","name":"$room"}$participant,"id":"EV_test","createdAt":"1727690400"}"""
    }
}

/** Отправляет серверу webhook от «LiveKit». */
suspend fun ApiTestScope.sendLiveKitWebhook(
    body: String,
    signature: String? = FakeLiveKit.signature(body),
): HttpResponse =
    client.post(ApiPaths.WEBHOOKS_LIVEKIT) {
        signature?.let { header(HttpHeaders.Authorization, it) }
        // Готовое тело: иначе ContentNegotiation клиента закодировал бы строку как JSON ещё раз.
        setBody(TextContent(body, ContentType.parse("application/webhook+json")))
    }
