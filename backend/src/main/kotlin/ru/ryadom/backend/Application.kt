package ru.ryadom.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.ryadom.backend.auth.AccessTokens
import ru.ryadom.backend.auth.AuthService
import ru.ryadom.backend.auth.RefreshTokenStore
import ru.ryadom.backend.auth.YandexIdClient
import ru.ryadom.backend.auth.authRoutes
import ru.ryadom.backend.auth.installSecurity
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.errors.installErrorHandling
import ru.ryadom.backend.errors.withoutMessages
import ru.ryadom.backend.livekit.LiveKitService
import ru.ryadom.backend.realtime.RealtimeHub
import ru.ryadom.backend.realtime.realtimeRoutes
import ru.ryadom.backend.requests.HelpRequestRepository
import ru.ryadom.backend.requests.HelpRequestService
import ru.ryadom.backend.requests.RequestDispatcher
import ru.ryadom.backend.requests.RequestLocks
import ru.ryadom.backend.requests.VolunteerMatcher
import ru.ryadom.backend.requests.liveKitWebhookRoutes
import ru.ryadom.backend.requests.requestRoutes
import ru.ryadom.backend.users.ProfileService
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.backend.users.profileRoutes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.HealthResponse
import java.time.Clock
import kotlin.time.toKotlinDuration

/** Точка входа. Настройки — `resources/application.conf` и переменные окружения (см. infra/.env.example). */
fun main() {
    val config = AppConfig.load()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }
        .start(wait = true)
}

/** Части приложения, которые нужны тестам напрямую. */
class AppComponents(
    /** Поиск волонтёров. В тестах фоновая проверка выключена, и тест вызывает `dispatcher.tick()` сам. */
    val dispatcher: RequestDispatcher,
)

/**
 * Сборка приложения. Вынесена отдельно, чтобы тесты поднимали сервер без сети,
 * со своими часами [clock] и поддельным HTTP-клиентом [httpClient] для Яндекс ID.
 * @param runBackgroundJobs запускать ли фоновую проверку волн и таймаутов (в тестах — нет).
 */
fun Application.module(
    config: AppConfig,
    clock: Clock = Clock.systemUTC(),
    httpClient: HttpClient = defaultHttpClient(),
    runBackgroundJobs: Boolean = true,
): AppComponents {
    val database = AppDatabase(config.database)
    monitor.subscribe(ApplicationStopped) {
        database.close()
        httpClient.close()
    }

    val users = UserRepository(database)
    val helpRequests = HelpRequestRepository(database)
    val profiles = ProfileService(users, helpRequests, config.profile)
    val accessTokens = AccessTokens(config.auth.jwt, clock)
    val refreshTokens = RefreshTokenStore(database, clock, config.auth.refreshTokenTtl)
    val auth = AuthService(users, refreshTokens, accessTokens, profiles, config.profile, clock)
    val yandex = config.auth.yandex?.let { YandexIdClient(it, httpClient) }

    val hub = RealtimeHub()
    val liveKit = LiveKitService(config.liveKit, clock)
    val locks = RequestLocks()
    val matcher = VolunteerMatcher(config.profile)
    val dispatcher =
        RequestDispatcher(helpRequests, users, matcher, hub, locks, config.matching, config.requests, clock)
    val requests = HelpRequestService(helpRequests, users, dispatcher, hub, liveKit, locks, config.requests, clock)

    if (config.auth.devLoginEnabled) {
        log.warn("Dev login (POST /auth/dev) is ENABLED. Never enable it on a public server.")
    }

    install(ContentNegotiation) {
        json(
            Json {
                // Поля со значениями по умолчанию тоже попадают в ответ — как описано в openapi.yaml.
                encodeDefaults = true
                // Неизвестные поля в запросе — ошибка 400: так опечатки в клиенте видны сразу.
                ignoreUnknownKeys = false
            },
        )
    }
    install(WebSockets) {
        // Ping выявляет пропавших клиентов (телефон ушёл из сети), и сервер перестаёт считать их на связи.
        pingPeriod = config.realtime.pingInterval.toKotlinDuration()
        timeout = config.realtime.pingInterval.toKotlinDuration()
        // Клиент присылает только короткие сообщения auth.
        maxFrameSize = MAX_CLIENT_FRAME_BYTES
    }
    // Логируем только метод, путь и статус — без тел запросов и заголовков (там могут быть токены).
    install(CallLogging)
    installErrorHandling()
    installSecurity(accessTokens)

    routing {
        get(ApiPaths.HEALTH) {
            call.respond(HealthResponse(HealthResponse.STATUS_OK))
        }
        authRoutes(auth, yandex, config.auth.devLoginEnabled)
        profileRoutes(profiles)
        requestRoutes(requests)
        liveKitWebhookRoutes(requests, liveKit)
        realtimeRoutes(hub, accessTokens, users, config.realtime, clock)
    }

    if (runBackgroundJobs) launchDispatcher(dispatcher, config.matching)
    return AppComponents(dispatcher)
}

/** Раз в `matching.checkInterval` отправляет очередные волны и закрывает просроченные поиски и звонки. */
private fun Application.launchDispatcher(
    dispatcher: RequestDispatcher,
    config: MatchingConfig,
) {
    launch {
        while (isActive) {
            delay(config.checkInterval.toKotlinDuration())
            try {
                dispatcher.tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Сбой (например, база недоступна) не останавливает проверки: следующая попытка — через checkInterval.
                log.error("Request dispatcher failed", e.withoutMessages())
            }
        }
    }
}

/** Максимальный размер сообщения от клиента в WebSocket. */
private const val MAX_CLIENT_FRAME_BYTES = 64L * 1024

/** HTTP-клиент для внешних сервисов входа: короткие таймауты, чтобы вход не зависал. */
private fun defaultHttpClient() =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    }
