package ru.ryadom.backend.auth

import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import ru.ryadom.backend.ProfileDefaults
import ru.ryadom.backend.errors.ApiException
import ru.ryadom.backend.users.IdentityProvider
import ru.ryadom.backend.users.NewUser
import ru.ryadom.backend.users.ProfileService
import ru.ryadom.backend.users.UserRecord
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.backend.users.normalizeDisplayName
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.UserProfile
import java.time.Clock

/** Вход, обновление токенов и выход. Не зависит от конкретного провайдера входа. */
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenStore,
    private val accessTokens: AccessTokens,
    private val profiles: ProfileService,
    private val defaults: ProfileDefaults,
    private val clock: Clock,
) {
    /**
     * Вход пользователя, которого провайдер знает как [subject]. При первом входе пользователь создаётся;
     * [suggestedDisplayName] (например, имя из Яндекса) становится его начальным именем.
     * Слишком длинное имя обрезается, а не приводит к отказу во входе.
     */
    suspend fun login(
        provider: IdentityProvider,
        subject: String,
        suggestedDisplayName: String?,
    ): AuthResponse {
        val newUser =
            NewUser(
                displayName = suggestedDisplayName?.let { normalizeDisplayName(it.trim().take(UserProfile.DISPLAY_NAME_MAX_LENGTH)) },
                language = defaults.language,
                timezone = defaults.timezone.id,
            )
        val (user, created) = users.findOrCreateByIdentity(provider, subject, newUser, clock.instant())
        if (created) log.info("User {} registered via {}", user.id, provider.dbValue)
        if (user.isBanned) {
            log.info("Banned user {} tried to log in", user.id)
            throw ApiException.userBanned()
        }
        return authResponse(user, refreshTokens.issue(user.id))
    }

    suspend fun refresh(refreshToken: String): AuthResponse =
        when (val result = refreshTokens.rotate(refreshToken)) {
            RotationResult.Invalid -> {
                throw invalidRefreshToken()
            }

            is RotationResult.Reused -> {
                log.warn("Refresh token reuse for user {}: all sessions revoked", result.userId)
                throw invalidRefreshToken()
            }

            is RotationResult.Rotated -> {
                val user = users.findById(result.userId) ?: throw invalidRefreshToken()
                if (user.isBanned) {
                    refreshTokens.revokeAll(user.id)
                    throw ApiException.userBanned()
                }
                authResponse(user, result.newToken)
            }
        }

    suspend fun logout(refreshToken: String) = refreshTokens.revoke(refreshToken)

    private fun authResponse(
        user: UserRecord,
        refreshToken: String,
    ) = AuthResponse(
        accessToken = accessTokens.issue(user.id),
        accessTokenExpiresIn = accessTokens.ttlSeconds,
        refreshToken = refreshToken,
        user = profiles.toProfile(user),
    )

    private fun invalidRefreshToken() =
        ApiException(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN, "Refresh token is invalid, expired or already used")

    private companion object {
        val log = LoggerFactory.getLogger(AuthService::class.java)
    }
}
