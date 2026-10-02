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
