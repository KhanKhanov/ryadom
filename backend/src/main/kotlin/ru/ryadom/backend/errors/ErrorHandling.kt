package ru.ryadom.backend.errors

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import ru.ryadom.shared.api.ApiError
import ru.ryadom.shared.api.ApiErrorCodes

/**
 * Ошибка, которую нужно вернуть клиенту в формате [ApiError].
 * [message] уходит клиенту — только на английском и без персональных данных.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun invalidRequest(message: String) = ApiException(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST, message)

        fun notFound(message: String = "Not found") = ApiException(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND, message)

        fun userBanned() = ApiException(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED, "User is banned")

        fun forbidden(message: String) = ApiException(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN, message)

        /** 409: действие противоречит текущему состоянию (например, запрос уже принят). */
        fun conflict(
            code: String,
            message: String,
        ) = ApiException(HttpStatusCode.Conflict, code, message)
    }
}

private val log = LoggerFactory.getLogger("ru.ryadom.backend.errors")

/** Все ошибки отдаются в одном формате [ApiError], чтобы клиентам было просто их разбирать. */
fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            if (e.status.value >= 500) {
                log.warn("{} {} -> {} {}: {}", call.request.httpMethod.value, call.request.path(), e.status.value, e.code, e.message)
            }
            call.respond(e.status, ApiError(e.code, e.message))
        }
        // Невалидный JSON, неверные типы полей или тело не в JSON. Текст исключения не возвращаем и не логируем:
        // в нём kotlinx.serialization цитирует тело запроса, а там могут быть персональные данные.
        exception<BadRequestException> { call, _ -> call.respondMalformedBody() }
        exception<ContentTransformationException> { call, _ -> call.respondMalformedBody() }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respondMalformedBody() }
        exception<Throwable> { call, cause ->
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), cause.withoutMessages())
            call.respond(HttpStatusCode.InternalServerError, ApiError(ApiErrorCodes.INTERNAL_ERROR, "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError(ApiErrorCodes.NOT_FOUND, "Not found"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError(ApiErrorCodes.INVALID_REQUEST, "Method not allowed"))
        }
    }
}

private suspend fun ApplicationCall.respondMalformedBody() =
    respond(
        HttpStatusCode.BadRequest,
        ApiError(ApiErrorCodes.INVALID_REQUEST, "Malformed request body: expected JSON (Content-Type: application/json)"),
    )

/**
 * Копия исключения со стеком, но без текстов сообщений: тексты ошибок базы данных и библиотек
 * могут содержать значения из запроса (имена и т. п.), а в логах — только id (CLAUDE.md, правило 3).
 */
internal fun Throwable.withoutMessages(depth: Int = 0): Throwable {
    val hiddenCause = if (depth < MAX_CAUSE_DEPTH) cause?.withoutMessages(depth + 1) else null
    val copy = RuntimeException("${this::class.java.name} (message hidden)", hiddenCause)
    copy.stackTrace = stackTrace
    return copy
}

private const val MAX_CAUSE_DEPTH = 10
