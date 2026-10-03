package ru.ryadom.shared.client

import ru.ryadom.shared.api.ApiErrorCodes

/** Почему закончился сеанс и нужно войти снова. */
enum class SessionEndReason {
    /** Refresh-токен истёк или отозван. */
    EXPIRED,

    /** Пользователь заблокирован. */
    BANNED,

    /** Пользователь вышел сам. */
    LOGGED_OUT,
}

/** Ошибки обращения к серверу. */
sealed class ApiClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Сервер ответил ошибкой. [code] — машинный код из ответа (`ApiErrorCodes`), по нему выбирается
     * текст для пользователя. Неизвестный клиенту код — не беда: покажем общее сообщение.
     */
    class Server(
        val status: Int,
        val code: String,
        message: String,
    ) : ApiClientException("HTTP $status $code: $message")

    /** До сервера не достучаться: нет сети, сервер выключен или перезапускается. */
    class Network(
        cause: Throwable? = null,
    ) : ApiClientException("Network request failed", cause)

    /** Сервер ответил не так, как описано в `docs/api/openapi.yaml`. */
    class UnexpectedResponse(
        message: String,
        cause: Throwable? = null,
    ) : ApiClientException(message, cause)

    /** Сеанса больше нет: токены удалены, нужно войти снова. */
    class SessionEnded(
        val reason: SessionEndReason,
    ) : ApiClientException("Session ended: $reason")
}

/** Код ошибки сервера, если это она. */
val Throwable.apiErrorCode: String?
    get() = (this as? ApiClientException.Server)?.code

/**
 * Ошибка действия в виде, понятном пользователю. Платформа показывает и объявляет текст
 * из своих ресурсов по этому значению.
 */
enum class UserError {
    /** Нет связи с сервером. */
    NETWORK,

    /** Слишком много запросов помощи за час. */
    TOO_MANY_REQUESTS,

    /** Вход через провайдера не удался (провайдер отклонил вход). */
    LOGIN_FAILED,

    /** Сервис входа (Яндекс ID) сейчас недоступен. */
    PROVIDER_UNAVAILABLE,

    /** Этот способ входа на сервере выключен (например, вход без OAuth на боевом сервере). */
    LOGIN_UNAVAILABLE,

    /** Роль не сменить, пока есть активный запрос или идёт звонок. */
    ACTIVE_REQUEST,

    /** Что-то пошло не так — общее сообщение, в том числе для неизвестных кодов ошибок. */
    UNKNOWN,
}

/** Какую ошибку показать пользователю. */
fun Throwable.toUserError(): UserError =
    when (this) {
        is ApiClientException.Network -> {
            UserError.NETWORK
        }

        is ApiClientException.Server -> {
            when (code) {
                ApiErrorCodes.TOO_MANY_REQUESTS -> UserError.TOO_MANY_REQUESTS
                ApiErrorCodes.OAUTH_FAILED -> UserError.LOGIN_FAILED
                ApiErrorCodes.PROVIDER_UNAVAILABLE -> UserError.PROVIDER_UNAVAILABLE
                ApiErrorCodes.ACTIVE_REQUEST_EXISTS -> UserError.ACTIVE_REQUEST
                else -> UserError.UNKNOWN
            }
        }

        else -> {
            UserError.UNKNOWN
        }
    }
