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
import kotlinx.serialization.json.Json
import ru.ryadom.backend.auth.AccessTokens
import ru.ryadom.backend.auth.AuthService
import ru.ryadom.backend.auth.RefreshTokenStore
import ru.ryadom.backend.auth.YandexIdClient
import ru.ryadom.backend.auth.authRoutes
import ru.ryadom.backend.auth.installSecurity
import ru.ryadom.backend.db.AppDatabase
import ru.ryadom.backend.errors.installErrorHandling
import ru.ryadom.backend.users.ProfileService
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.backend.users.profileRoutes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.HealthResponse
import java.time.Clock

/** Точка входа. Настройки — `resources/application.conf` и переменные окружения (см. infra/.env.example). */
fun main() {
    val config = AppConfig.load()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(config) }
        .start(wait = true)
}

/**
 * Сборка приложения. Вынесена отдельно, чтобы тесты поднимали сервер без сети,
 * со своими часами [clock] и поддельным HTTP-клиентом [httpClient] для Яндекс ID.
 */
fun Application.module(
    config: AppConfig,
    clock: Clock = Clock.systemUTC(),
    httpClient: HttpClient = defaultHttpClient(),
) {
    val database = AppDatabase(config.database)
    monitor.subscribe(ApplicationStopped) {
        database.close()
        httpClient.close()
    }

    val users = UserRepository(database)
    val profiles = ProfileService(users, config.profile)
    val accessTokens = AccessTokens(config.auth.jwt, clock)
    val refreshTokens = RefreshTokenStore(database, clock, config.auth.refreshTokenTtl)
    val auth = AuthService(users, refreshTokens, accessTokens, profiles, config.profile, clock)
    val yandex = config.auth.yandex?.let { YandexIdClient(it, httpClient) }

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
    }
}

/** HTTP-клиент для внешних сервисов входа: короткие таймауты, чтобы вход не зависал. */
private fun defaultHttpClient() =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    }
