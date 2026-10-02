package ru.ryadom.shared.api

import kotlinx.serialization.Serializable

/** Ответ `GET /health`: сервер жив и отвечает. Схема — `HealthResponse` в `docs/api/openapi.yaml`. */
@Serializable
data class HealthResponse(
    val status: String,
) {
    companion object {
        const val STATUS_OK = "ok"
    }
}

/** Пути REST API. Держим в одном месте, чтобы сервер и клиенты не расходились. */
object ApiPaths {
    const val HEALTH = "/health"
}
