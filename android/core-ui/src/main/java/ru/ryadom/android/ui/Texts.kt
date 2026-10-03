package ru.ryadom.android.ui

import androidx.annotation.StringRes
import ru.ryadom.shared.client.UserError

/** Текст ошибки для пользователя (CLAUDE.md, правило 2: строки — только в ресурсах). */
@get:StringRes
val UserError.messageRes: Int
    get() =
        when (this) {
            UserError.NETWORK -> R.string.error_network
            UserError.TOO_MANY_REQUESTS -> R.string.error_too_many_requests
            UserError.LOGIN_FAILED -> R.string.error_login_failed
            UserError.PROVIDER_UNAVAILABLE -> R.string.error_provider_unavailable
            UserError.LOGIN_UNAVAILABLE -> R.string.error_login_unavailable
            UserError.ACTIVE_REQUEST -> R.string.error_active_request
            UserError.UNKNOWN -> R.string.error_unknown
        }
