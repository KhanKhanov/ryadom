package ru.ryadom.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.HealthResponse

/** Точка входа. Порт берётся из переменной окружения `PORT` (по умолчанию 8080). */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/** Настройка приложения. Вынесена отдельно, чтобы тесты поднимали сервер без сети. */
fun Application.module() {
    install(ContentNegotiation) { json() }
    // Логируем только метод, путь и статус — без тел запросов и заголовков (там могут быть токены).
    install(CallLogging)

    routing {
        get(ApiPaths.HEALTH) {
            call.respond(HealthResponse(HealthResponse.STATUS_OK))
        }
    }
}
