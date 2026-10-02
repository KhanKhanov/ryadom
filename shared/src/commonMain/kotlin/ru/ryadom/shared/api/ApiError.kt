package ru.ryadom.shared.api

import kotlinx.serialization.Serializable

/**
 * Ошибка API (схема `Error` в `docs/api/openapi.yaml`).
 * [code] — для программ: клиент показывает по нему текст из своих ресурсов.
 * [message] — пояснение для разработчика на английском, пользователю не показывается.
 */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

/**
 * Коды ошибок. Это строки, а не enum: сервер может добавить новый код,
 * и старая версия приложения не должна из-за этого падать.
 */
object ApiErrorCodes {
    const val INVALID_REQUEST = "invalid_request"
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_REFRESH_TOKEN = "invalid_refresh_token"
    const val OAUTH_FAILED = "oauth_failed"
    const val USER_BANNED = "user_banned"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val INTERNAL_ERROR = "internal_error"
}
