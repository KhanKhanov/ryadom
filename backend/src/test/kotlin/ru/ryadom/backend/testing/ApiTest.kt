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
import ru.ryadom.backend.PushSettings
import ru.ryadom.backend.RealtimeConfig
import ru.ryadom.backend.RequestsConfig
import ru.ryadom.backend.WebPushSettings
import ru.ryadom.backend.YandexConfig
import ru.ryadom.backend.module
import ru.ryadom.backend.push.Base64Url
import ru.ryadom.backend.push.VapidKeys
import ru.ryadom.shared.api.ApiError
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.Device
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.api.WebPushKeys
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

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
    // Отправители push в тестах поддельные (FakePush); настройки нужны регистрации устройств и GET /push/config.
    push =
        PushSettings(
            maxDevicesPerUser = 10,
            webPushHosts = listOf("fcm.googleapis.com", ".push.services.mozilla.com", ".push.apple.com"),
            webPush = TestWebPush.settings,
            fcm = null,
            ruStore = null,
        ),
)

/** Ключи Web Push для тестов — новые при каждом запуске, в репозитории их нет. */
object TestWebPush {
    val settings: WebPushSettings by lazy {
        val (publicKey, privateKey) = VapidKeys.generate()
        WebPushSettings(publicKey, privateKey, subject = "mailto:test@ryadom.test")
    }

    /** Новая подписка браузера: ключи настоящие, адрес — push-сервиса Chrome. */
    fun subscription(id: String = Uuid.random().toString()): RegisterDeviceRequest {
        val (p256dh, _) = VapidKeys.generate()
        return RegisterDeviceRequest(
            provider = PushProvider.WEB_PUSH,
            token = "https://fcm.googleapis.com/fcm/send/$id",
            webPush = WebPushKeys(p256dh = p256dh, auth = Base64Url.encode(ByteArray(16) { it.toByte() })),
        )
    }
}

/** Окружение теста API: клиент к серверу в памяти, управляемые часы, поддельные Яндекс и push-сервисы. */
class ApiTestScope(
    val client: HttpClient,
    val clock: TestClock,
    val yandex: FakeYandex,
    private val fakePush: FakePush,
    private val components: AppComponents,
) {
    /** Отправленные push-уведомления — после того, как рассылка закончилась. */
    suspend fun pushes(): FakePush {
        components.push.awaitIdle()
        return fakePush
    }

    /** Регистрирует устройство для push-уведомлений; регистрация должна пройти. */
    suspend fun registerDevice(
        user: AuthResponse,
        request: RegisterDeviceRequest = TestWebPush.subscription(),
    ): Device {
        val response = postJson(ApiPaths.DEVICES, request) { auth(user) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

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
 * Каждый ответ сервера проверяется по `docs/api/openapi.yaml` ([OpenApiContract]).
 */
fun apiTest(
    config: AppConfig = testConfig(),
    push: FakePush = FakePush(),
    block: suspend ApiTestScope.() -> Unit,
) = testApplication {
    TestDatabase.clean()
    val clock = TestClock()
    val yandex = FakeYandex()
    lateinit var components: AppComponents
    application { components = module(config, clock, yandex.httpClient, runBackgroundJobs = false, pushSenders = push.senders) }
    startApplication()
    val client =
        createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
            // Каждый ответ сервера сверяется с docs/api/openapi.yaml.
            install(OpenApiContract.plugin)
        }
    val scope = ApiTestScope(client, clock, yandex, push, components)
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
