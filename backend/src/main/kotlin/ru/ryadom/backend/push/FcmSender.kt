package ru.ryadom.backend.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import ru.ryadom.shared.api.PushMessage
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Сервисный аккаунт Firebase (файл JSON из консоли Firebase: «Настройки проекта → Сервисные аккаунты →
 * Создать закрытый ключ»). Из файла нужны id проекта, адрес аккаунта и закрытый ключ RSA.
 */
class FcmSettings(
    val projectId: String,
    val clientEmail: String,
    val privateKey: RSAPrivateKey,
    /** Куда обменивать подписанный JWT на токен доступа Google. */
    val tokenUri: String,
) {
    override fun toString() = "FcmSettings(projectId=$projectId, clientEmail=$clientEmail, privateKey=***)"

    companion object {
        /** Читает файл сервисного аккаунта. Падает с понятным сообщением, если файла нет или он не такой. */
        fun fromServiceAccountFile(path: String): FcmSettings {
            val file = File(path)
            check(file.isFile) { "FCM_SERVICE_ACCOUNT_FILE: файл $path не найден" }
            val account =
                try {
                    json.decodeFromString<ServiceAccount>(file.readText())
                } catch (e: SerializationException) {
                    throw IllegalStateException("FCM_SERVICE_ACCOUNT_FILE: это не файл сервисного аккаунта Firebase (JSON)", e)
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("FCM_SERVICE_ACCOUNT_FILE: это не файл сервисного аккаунта Firebase (JSON)", e)
                }
            return FcmSettings(account.projectId, account.clientEmail, parsePrivateKey(account.privateKey), account.tokenUri)
        }

        /** Закрытый ключ из файла сервисного аккаунта: PEM «PRIVATE KEY» (PKCS#8). */
        internal fun parsePrivateKey(pem: String): RSAPrivateKey {
            val base64 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))) as RSAPrivateKey
            } catch (e: Exception) {
                throw IllegalStateException("FCM_SERVICE_ACCOUNT_FILE: закрытый ключ (private_key) повреждён", e)
            }
        }

        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class ServiceAccount(
        @SerialName("project_id") val projectId: String,
        @SerialName("client_email") val clientEmail: String,
        @SerialName("private_key") val privateKey: String,
        @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
    )
}

/**
 * Firebase Cloud Messaging, HTTP v1 API (https://firebase.google.com/docs/cloud-messaging/send/v1-api).
 * Уведомление — только данные (`data`), без готового текста: показывает его само приложение (этап 6).
 * Приоритет высокий — так Android доставит вызов сразу, даже телефону в режиме экономии энергии.
 * Поле `message.token` в документации FCM с 2026 года помечено устаревшим в пользу `fid`
 * (Firebase Installation ID), но в переходный период принимает и его — что передаёт приложение, решается на этапе 6.
 *
 * Токен доступа Google получается по подписанному ключом сервисного аккаунта JWT
 * (https://developers.google.com/identity/protocols/oauth2/service-account#httprest) и живёт час.
 */
class FcmSender(
    private val settings: FcmSettings,
    private val http: HttpClient,
    private val clock: Clock,
    private val apiUrl: String = "https://fcm.googleapis.com",
) : PushSender {
    private val tokenLock = Mutex()
    private var accessToken: AccessToken? = null

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
                        put(
                            "android",
                            buildJsonObject {
                                put("priority", "high")
                                put("ttl", "${ttl.seconds.coerceAtLeast(0)}s")
                            },
                        )
                    },
                )
            }.toString()
        // Токен доступа мог истечь раньше срока (отозван) — тогда один раз получаем новый и повторяем.
        for (attempt in 1..2) {
            val token = accessToken(forceRefresh = attempt > 1) ?: return PushResult.FAILED
            val response =
                request(device) {
                    http.post("$apiUrl/v1/projects/${settings.projectId}/messages:send") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                } ?: return PushResult.FAILED
            if (response.status.value == HTTP_UNAUTHORIZED && attempt == 1) continue
            return result(device, response)
        }
        return PushResult.FAILED
    }

    private suspend fun result(
        device: DeviceRecord,
        response: HttpResponse,
    ): PushResult {
        if (response.status.value in 200..299) return PushResult.SENT
        val error = fcmErrorCode(response.bodyAsText())
        // Токен больше не действует: приложение удалено или токен выдан другому проекту Firebase.
        if (error in GONE_ERRORS) return PushResult.DEVICE_GONE
        log.warn("FCM to device {} failed: HTTP {} {}", device.id, response.status.value, error)
        return PushResult.FAILED
    }

    /** Действующий токен доступа Google; `null` — получить не удалось (подробности в логе). */
    private suspend fun accessToken(forceRefresh: Boolean): String? =
        tokenLock.withLock {
            val now = clock.instant()
            accessToken?.takeIf { !forceRefresh && it.expiresAt.isAfter(now.plus(TOKEN_REFRESH_MARGIN)) }?.let { return@withLock it.value }
            val assertion =
                JWT
                    .create()
                    .withIssuer(settings.clientEmail)
                    .withAudience(settings.tokenUri)
                    .withClaim("scope", SCOPE)
                    .withIssuedAt(now)
                    .withExpiresAt(now.plus(ASSERTION_TTL))
                    .sign(Algorithm.RSA256(null, settings.privateKey))
            val response =
                try {
                    http.submitForm(
                        url = settings.tokenUri,
                        formParameters =
                            parameters {
                                append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                                append("assertion", assertion)
                            },
                    )
                } catch (e: IOException) {
                    log.warn("Google OAuth is unreachable: {}", e::class.java.simpleName)
                    return@withLock null
                }
            if (response.status.value != HTTP_OK) {
                // Чаще всего — ключ сервисного аккаунта удалён в консоли Google Cloud.
                log.error("Google OAuth rejected the FCM service account: HTTP {}", response.status.value)
                return@withLock null
            }
            val token =
                try {
                    json.decodeFromString<TokenResponse>(response.bodyAsText())
                } catch (e: SerializationException) {
                    log.warn("Unexpected response from Google OAuth")
                    return@withLock null
                }
            accessToken = AccessToken(token.accessToken, now.plusSeconds(token.expiresIn))
            token.accessToken
        }

    private suspend fun request(
        device: DeviceRecord,
        block: suspend () -> HttpResponse,
    ): HttpResponse? =
        try {
            block()
        } catch (e: IOException) {
            log.warn("FCM to device {} failed: {}", device.id, e::class.java.simpleName)
            null
        }

    private class AccessToken(
        val value: String,
        val expiresAt: Instant,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        val ASSERTION_TTL: Duration = Duration.ofHours(1)
        val TOKEN_REFRESH_MARGIN: Duration = Duration.ofMinutes(5)

        /** Коды FCM, после которых токен устройства не оживёт (https://firebase.google.com/docs/reference/fcm/rest/v1/ErrorCode). */
        val GONE_ERRORS = setOf("UNREGISTERED", "SENDER_ID_MISMATCH")

        val json = Json { ignoreUnknownKeys = true }
        val log = LoggerFactory.getLogger(FcmSender::class.java)
    }
}

/**
 * Код ошибки FCM из ответа: `error.details[].errorCode` (`UNREGISTERED`, `QUOTA_EXCEEDED`…),
 * а если его нет — `error.status`. `null` — ответ не в формате Google API.
 */
internal fun fcmErrorCode(body: String): String? =
    try {
        val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
        val details = (error?.get("details") as? kotlinx.serialization.json.JsonArray).orEmpty()
        details
            .mapNotNull { ((it as? JsonObject)?.get("errorCode") as? JsonPrimitive)?.content }
            .firstOrNull()
            ?: (error?.get("status") as? JsonPrimitive)?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
