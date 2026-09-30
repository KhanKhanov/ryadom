package ru.ryadom.shared.api

/** Пути REST API. Держим в одном месте, чтобы сервер и клиенты не расходились. */
object ApiPaths {
    const val HEALTH = "/health"

    const val AUTH_DEV = "/auth/dev"
    const val AUTH_REFRESH = "/auth/refresh"
    const val AUTH_LOGOUT = "/auth/logout"

    /** Имя параметра пути в [AUTH_OAUTH]. */
    const val PROVIDER_PARAM = "provider"

    /** Шаблон пути для сервера; клиенту удобнее [authOAuth]. */
    const val AUTH_OAUTH = "/auth/oauth/{$PROVIDER_PARAM}"

    const val ME = "/me"

    fun authOAuth(provider: OAuthProvider): String = "/auth/oauth/${provider.pathValue}"
}
