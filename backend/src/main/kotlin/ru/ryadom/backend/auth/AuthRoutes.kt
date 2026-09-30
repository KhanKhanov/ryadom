package ru.ryadom.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.backend.users.IdentityProvider
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.OAuthLoginRequest
import ru.ryadom.shared.api.OAuthProvider
import ru.ryadom.shared.api.RefreshTokenRequest

/** PKCE (RFC 7636): длина code_verifier. */
private val CODE_VERIFIER_LENGTH = 43..128

/**
 * Маршруты входа и токенов (`/auth/...`).
 * @param yandex `null`, если вход через Яндекс ID не настроен.
 * @param devLoginEnabled включает `POST /auth/dev`; на боевом сервере маршрута просто нет (404).
 */
fun Route.authRoutes(
    auth: AuthService,
    yandex: YandexIdClient?,
    devLoginEnabled: Boolean,
) {
    if (devLoginEnabled) {
        post(ApiPaths.AUTH_DEV) {
            val request = call.receive<DevLoginRequest>()
            if (!DevLoginRequest.LOGIN_REGEX.matches(request.login)) {
                throw ApiException.invalidRequest("login must match ${DevLoginRequest.LOGIN_REGEX.pattern}")
            }
            call.respond(auth.login(IdentityProvider.DEV, request.login, suggestedDisplayName = request.login))
        }
    }

    post(ApiPaths.AUTH_OAUTH) {
        val provider =
            OAuthProvider.fromPathValue(call.parameters[ApiPaths.PROVIDER_PARAM].orEmpty())
                ?: throw ApiException.notFound("Unknown login provider")
        val request = call.receive<OAuthLoginRequest>().validated()
        when (provider) {
            OAuthProvider.YANDEX -> {
                val client = yandex ?: throw ApiException.notFound("Yandex ID login is not configured on this server")
                val yandexToken = request.accessToken ?: client.exchangeCode(checkNotNull(request.code), request.codeVerifier)
                val yandexUser = client.fetchUser(yandexToken)
                call.respond(auth.login(IdentityProvider.YANDEX, yandexUser.id, suggestedDisplayName = yandexUser.firstName))
            }
        }
    }

    post(ApiPaths.AUTH_REFRESH) {
        val request = call.receive<RefreshTokenRequest>().validated()
        call.respond(auth.refresh(request.refreshToken))
    }

    post(ApiPaths.AUTH_LOGOUT) {
        val request = call.receive<RefreshTokenRequest>().validated()
        auth.logout(request.refreshToken)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun OAuthLoginRequest.validated(): OAuthLoginRequest {
    val hasCode = !code.isNullOrBlank()
    val hasToken = !accessToken.isNullOrBlank()
    if (hasCode == hasToken) throw ApiException.invalidRequest("Exactly one of code or accessToken is required")
    val verifier = codeVerifier
    if (verifier != null) {
        if (!hasCode) throw ApiException.invalidRequest("codeVerifier is allowed only with code")
        if (verifier.length !in CODE_VERIFIER_LENGTH) {
            throw ApiException.invalidRequest("codeVerifier must be 43..128 characters")
        }
    }
    return this
}

private fun RefreshTokenRequest.validated(): RefreshTokenRequest {
    if (refreshToken.isBlank()) throw ApiException.invalidRequest("refreshToken must not be empty")
    return this
}
