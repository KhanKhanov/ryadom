package ru.ryadom.backend.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import ru.ryadom.shared.api.ApiError
import ru.ryadom.shared.api.ApiErrorCodes
import kotlin.uuid.Uuid

/** Имя схемы авторизации для `authenticate(JWT_AUTH) { ... }`. */
const val JWT_AUTH = "jwt"

/** Пользователь, вошедший по access-токену. */
data class UserPrincipal(
    val userId: Uuid,
)

fun Application.installSecurity(accessTokens: AccessTokens) {
    install(Authentication) {
        jwt(JWT_AUTH) {
            verifier(accessTokens.verifier)
            validate { credential ->
                credential.payload.subject
                    ?.let { Uuid.parseOrNull(it) }
                    ?.let { UserPrincipal(it) }
            }
            challenge { _, _ ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiErrorCodes.UNAUTHORIZED, "Missing, invalid or expired access token"),
                )
            }
        }
    }
}

/** id текущего пользователя. Вызывать только внутри `authenticate(JWT_AUTH)`. */
val ApplicationCall.userId: Uuid get() = checkNotNull(principal<UserPrincipal>()).userId
