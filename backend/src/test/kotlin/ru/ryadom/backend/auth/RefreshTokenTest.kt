package ru.ryadom.backend.auth

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.auth
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.RefreshTokenRequest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RefreshTokenTest {
    @Test
    fun refreshReturnsNewWorkingPair() =
        apiTest {
            val login = devLogin()

            val response = postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))

            assertEquals(HttpStatusCode.OK, response.status)
            val refreshed = response.body<AuthResponse>()
            assertNotEquals(login.refreshToken, refreshed.refreshToken)
            assertEquals(login.user.id, refreshed.user.id)
            assertEquals(HttpStatusCode.OK, client.get(ApiPaths.ME) { auth(refreshed) }.status)
        }

    @Test
    fun refreshTokenWorksOnlyOnce() =
        apiTest {
            val login = devLogin()
            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))

            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN)
        }

    @Test
    fun reuseOfOldTokenRevokesAllSessions() =
        apiTest {
            val login = devLogin()
            val otherDevice = devLogin()
            val refreshed = postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken)).body<AuthResponse>()

            // Кто-то повторно использовал старый токен — значит, он мог быть украден.
            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))

            for (token in listOf(refreshed.refreshToken, otherDevice.refreshToken)) {
                postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(token))
                    .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN)
            }
        }

    @Test
    fun expiredRefreshTokenIsRejected() =
        apiTest {
            val login = devLogin()
            clock.advance(Duration.ofDays(90))

            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN)
        }

    @Test
    fun refreshExtendsSession() =
        apiTest {
            var tokens = devLogin()
            // Пользователь заходит раз в два месяца — сессия не должна истекать.
            repeat(3) {
                clock.advance(Duration.ofDays(60))
                val response = postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(tokens.refreshToken))
                assertEquals(HttpStatusCode.OK, response.status)
                tokens = response.body()
            }
        }

    @Test
    fun expiredTokensAreCleanedUpOnNextLogin() =
        apiTest {
            devLogin()
            clock.advance(Duration.ofDays(91))

            devLogin()

            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM refresh_tokens"))
        }

    @Test
    fun unknownTokenIsRejected() =
        apiTest {
            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest("made-up"))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN)
            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(""))
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
        }

    @Test
    fun bannedUserCannotRefresh() =
        apiTest {
            val login = devLogin()
            TestDatabase.execute("UPDATE users SET banned_at = now()")

            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))
                .assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
            assertEquals(0, TestDatabase.queryInt("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL"))
        }

    @Test
    fun logoutRevokesToken() =
        apiTest {
            val login = devLogin()

            assertEquals(HttpStatusCode.NoContent, postJson(ApiPaths.AUTH_LOGOUT, RefreshTokenRequest(login.refreshToken)).status)
            // Повторный выход тоже успешен.
            assertEquals(HttpStatusCode.NoContent, postJson(ApiPaths.AUTH_LOGOUT, RefreshTokenRequest(login.refreshToken)).status)

            postJson(ApiPaths.AUTH_REFRESH, RefreshTokenRequest(login.refreshToken))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.INVALID_REFRESH_TOKEN)
        }

    @Test
    fun onlyTokenHashIsStored() =
        apiTest {
            val login = devLogin()

            val hashed = "sha256(convert_to('${login.refreshToken}', 'UTF8'))"
            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM refresh_tokens WHERE token_hash = $hashed"))
            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM refresh_tokens"))
        }
}
