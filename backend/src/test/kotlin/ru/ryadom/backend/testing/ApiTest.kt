package ru.ryadom.backend.testing

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import ru.ryadom.backend.AppConfig
import ru.ryadom.backend.AuthConfig
import ru.ryadom.backend.JwtConfig
import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.YandexConfig
import ru.ryadom.backend.module
import ru.ryadom.shared.api.ApiError
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.Language
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

const val TEST_JWT_SECRET = "test-secret-that-is-at-least-32-characters-long"

fun testConfig(
    devLoginEnabled: Boolean = true,
    yandex: YandexConfig? = FakeYandex.config,
) = AppConfig(
    port = 0,
    database = TestDatabase.config,
    auth =
        AuthConfig(
            jwt = JwtConfig(TEST_JWT_SECRET, "ryadom", "ryadom-api", Duration.ofMinutes(15)),
            refreshTokenTtl = Duration.ofDays(90),
            devLoginEnabled = devLoginEnabled,
            yandex = yandex,
        ),
    profile =
        ProfileDefaults(
            language = Language.RU,
            timezone = ZoneId.of("Europe/Moscow"),
            doNotDisturbFrom = LocalTime.of(22, 0),
            doNotDisturbTo = LocalTime.of(8, 0),
        ),
)

/** Окружение теста API: клиент к серверу в памяти, управляемые часы и поддельный Яндекс. */
class ApiTestScope(
    val client: HttpClient,
    val clock: TestClock,
    val yandex: FakeYandex,
) {
    suspend fun postJson(
        path: String,
        body: Any,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            block()
        }

    /** Входит через `POST /auth/dev` и возвращает ответ с токенами. */
    suspend fun devLogin(login: String = "user-1"): AuthResponse {
        val response = postJson(ApiPaths.AUTH_DEV, DevLoginRequest(login))
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }
}

/**
 * Поднимает сервер в памяти с чистой базой в Docker. Пример:
 * ```
 * @Test fun example() = apiTest { val auth = devLogin(); ... }
 * ```
 */
fun apiTest(
    config: AppConfig = testConfig(),
    block: suspend ApiTestScope.() -> Unit,
) = testApplication {
    TestDatabase.clean()
    val clock = TestClock()
    val yandex = FakeYandex()
    application { module(config, clock, yandex.httpClient) }
    val client = createClient { install(ContentNegotiation) { json() } }
    ApiTestScope(client, clock, yandex).block()
}

fun HttpRequestBuilder.auth(response: AuthResponse) = bearerAuth(response.accessToken)

/** Проверяет статус и код ошибки в теле ответа. */
suspend fun HttpResponse.assertError(
    status: HttpStatusCode,
    code: String,
) {
    assertEquals(status, this.status)
    assertEquals(code, body<ApiError>().code)
}
