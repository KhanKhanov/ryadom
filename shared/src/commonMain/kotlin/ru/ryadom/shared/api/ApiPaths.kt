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

    const val REQUESTS = "/requests"
    const val REQUESTS_CURRENT = "/requests/current"

    /** Имя параметра пути в [REQUEST], [REQUEST_ACCEPT] и [REQUEST_RATING]. */
    const val REQUEST_ID_PARAM = "requestId"

    // Шаблоны путей для сервера; клиенту удобнее функции request(), requestAccept(), requestRating().
    const val REQUEST = "/requests/{$REQUEST_ID_PARAM}"
    const val REQUEST_ACCEPT = "$REQUEST/accept"
    const val REQUEST_RATING = "$REQUEST/rating"

    /** WebSocket событий в реальном времени (см. [ClientMessage] и [ServerEvent]). */
    const val REALTIME = "/ws"

    /** Сюда сервер LiveKit присылает события комнат. Клиенты этот путь не вызывают. */
    const val WEBHOOKS_LIVEKIT = "/webhooks/livekit"

    fun authOAuth(provider: OAuthProvider): String = "/auth/oauth/${provider.pathValue}"

    fun request(requestId: String): String = "$REQUESTS/$requestId"

    fun requestAccept(requestId: String): String = "${request(requestId)}/accept"

    fun requestRating(requestId: String): String = "${request(requestId)}/rating"
}
