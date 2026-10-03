package ru.ryadom.backend.testing

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import ru.ryadom.backend.AppComponents
import ru.ryadom.backend.AppConfig
import ru.ryadom.backend.AuthConfig
import ru.ryadom.backend.JwtConfig
import ru.ryadom.backend.MatchingConfig
import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.RealtimeConfig
import ru.ryadom.backend.RequestsConfig
import ru.ryadom.backend.YandexConfig
import ru.ryadom.backend.module
import ru.ryadom.shared.api.ApiError
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
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
    // Те же значения, что в application.conf.
    matching =
        MatchingConfig(
            firstWaveSize = 5,
            nextWaveSize = 10,
            waveInterval = Duration.ofSeconds(10),
            searchTimeout = Duration.ofSeconds(60),
            checkInterval = Duration.ofSeconds(1),
        ),
    requests =
        RequestsConfig(
            maxRequestsPerWindow = 20,
            rateLimitWindow = Duration.ofHours(1),
            maxCallDuration = Duration.ofHours(3),
            joinTimeout = Duration.ofMinutes(2),
        ),
    // Короткое ожидание входа, чтобы тест «не прислал auth» не ждал 10 секунд.
    realtime = RealtimeConfig(authTimeout = Duration.ofMillis(500), pingInterval = Duration.ofSeconds(30)),
    liveKit = FakeLiveKit.config,
)

/** Окружение теста API: клиент к серверу в памяти, управляемые часы и поддельный Яндекс. */
class ApiTestScope(
    val client: HttpClient,
    val clock: TestClock,
    val yandex: FakeYandex,
    private val components: AppComponents,
) {
    private val connections = mutableListOf<RealtimeTestClient>()

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

    /** Входит и выбирает роль. */
    suspend fun login(
        login: String,
        role: SelectableRole,
    ): AuthResponse {
        val auth = devLogin(login)
        val response =
            client.patch(ApiPaths.ME) {
                auth(auth)
                contentType(ContentType.Application.Json)
                setBody(UpdateProfileRequest(role = role))
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return auth.copy(user = response.body<UserProfile>())
    }

    suspend fun blind(login: String = "blind-1"): AuthResponse = login(login, SelectableRole.BLIND)

    suspend fun volunteer(login: String): AuthResponse = login(login, SelectableRole.VOLUNTEER)

    /** Открывает WebSocket `/ws` без входа. */
    suspend fun openRealtime(): RealtimeTestClient =
        RealtimeTestClient(client.webSocketSession(ApiPaths.REALTIME)).also { connections += it }

    /** Открывает WebSocket `/ws`, входит и дожидается `ready`. */
    suspend fun connect(auth: AuthResponse): RealtimeTestClient {
        val connection = openRealtime()
        connection.send(ClientMessage.Auth(auth.accessToken))
        assertEquals(ServerEvent.Ready, connection.next())
        return connection
    }

    /** Одна проверка поиска (волны, таймауты) — как будто прошёл `matching.checkInterval`. */
    suspend fun tick() = components.dispatcher.tick()

    internal suspend fun closeConnections() = connections.forEach { it.close() }
}

/**
 * Поднимает сервер в памяти с чистой базой в Docker. Пример:
 * ```
 * @Test fun example() = apiTest { val auth = devLogin(); ... }
 * ```
 * Фоновая проверка поиска выключена: время двигает тест (`clock.advance`), проверку запускает `tick()`.
 */
fun apiTest(
    config: AppConfig = testConfig(),
    block: suspend ApiTestScope.() -> Unit,
) = testApplication {
    TestDatabase.clean()
    val clock = TestClock()
    val yandex = FakeYandex()
    lateinit var components: AppComponents
    application { components = module(config, clock, yandex.httpClient, runBackgroundJobs = false) }
    startApplication()
    val client =
        createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
    val scope = ApiTestScope(client, clock, yandex, components)
    try {
        scope.block()
    } finally {
        scope.closeConnections()
    }
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
