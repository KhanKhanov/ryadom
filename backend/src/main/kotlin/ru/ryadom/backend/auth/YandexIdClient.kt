package ru.ryadom.backend.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.ryadom.backend.YandexConfig
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.shared.api.ApiErrorCodes
import java.io.IOException

/** Пользователь Яндекса — только то, что мы используем. */
data class YandexUser(
    /** Постоянный идентификатор пользователя в Яндексе. */
    val id: String,
    /** Имя, если приложению дан доступ `login:info`. */
    val firstName: String?,
)

/**
 * Клиент Яндекс ID (https://yandex.ru/dev/id/doc/ru/).
 * Токены Яндекса никуда не сохраняются и не пишутся в логи.
 */
class YandexIdClient(
    private val config: YandexConfig,
    private val http: HttpClient,
) {
    /** Обменивает код авторизации (веб) на токен Яндекса. */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String?,
    ): String {
        val response =
            request {
                http.submitForm(
                    url = "${config.oauthUrl}/token",
                    formParameters =
                        parameters {
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("client_id", config.clientId)
                            config.clientSecret?.let { append("client_secret", it) }
                            codeVerifier?.let { append("code_verifier", it) }
                        },
                )
            }
        val body = response.bodyAsText()
        return when (response.status) {
            HttpStatusCode.OK -> {
                parse<TokenResponse>(body).accessToken
            }

            HttpStatusCode.BadRequest -> {
                val error = runCatching { parse<TokenError>(body).error }.getOrNull()
                if (error == "invalid_client") {
                    // Ошибка настройки нашего сервера, а не пользователя.
                    log.error("Yandex rejected client credentials: check YANDEX_CLIENT_ID and YANDEX_CLIENT_SECRET")
                    throw providerUnavailable()
                }
                log.info("Yandex rejected authorization code: {}", error)
                throw oauthFailed("Authorization code was rejected by Yandex")
            }

            else -> {
                log.warn("Yandex token endpoint answered {}", response.status.value)
                throw providerUnavailable()
            }
        }
    }

    /**
     * Узнаёт пользователя по токену Яндекса. Проверяет, что токен выдан нашему приложению:
     * иначе токен, полученный любым другим сайтом с входом через Яндекс, позволил бы войти к нам.
     */
    suspend fun fetchUser(accessToken: String): YandexUser {
        val response =
            request {
                http.get("${config.loginUrl}/info") {
                    parameter("format", "json")
                    header(HttpHeaders.Authorization, "OAuth $accessToken")
                }
            }
        when (response.status) {
            HttpStatusCode.OK -> {}

            HttpStatusCode.Unauthorized -> {
                throw oauthFailed("Yandex token is invalid or expired")
            }

            else -> {
                log.warn("Yandex user info endpoint answered {}", response.status.value)
                throw providerUnavailable()
            }
        }
        val info = parse<UserInfo>(response.bodyAsText())
        if (info.clientId !in config.trustedClientIds) {
            log.warn("Yandex token was issued for untrusted client_id {}", info.clientId)
            throw oauthFailed("Yandex token was issued for another application")
        }
        if (info.id.isBlank()) throw providerUnavailable()
        return YandexUser(id = info.id, firstName = info.firstName)
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: IOException) {
            // Таймауты Ktor тоже наследуются от IOException.
            log.warn("Yandex ID is unreachable: {}", e::class.java.simpleName)
            throw providerUnavailable(e)
        }

    private inline fun <reified T> parse(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            log.warn("Unexpected response from Yandex ID")
            throw providerUnavailable(e)
        } catch (e: IllegalArgumentException) {
            log.warn("Unexpected response from Yandex ID")
            throw providerUnavailable(e)
        }

    private fun oauthFailed(message: String) = ApiException(HttpStatusCode.Unauthorized, ApiErrorCodes.OAUTH_FAILED, message)

    private fun providerUnavailable(cause: Throwable? = null) =
        ApiException(HttpStatusCode.BadGateway, ApiErrorCodes.PROVIDER_UNAVAILABLE, "Yandex ID is unavailable", cause)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
    )

    @Serializable
    private data class TokenError(
        val error: String,
    )

    @Serializable
    private data class UserInfo(
        val id: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("first_name") val firstName: String? = null,
    )

    private companion object {
        val log = LoggerFactory.getLogger(YandexIdClient::class.java)
        val json = Json { ignoreUnknownKeys = true }
    }
}
