package ru.ryadom.backend.auth

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.testConfig
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.DevLoginRequest
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevLoginTest {
    @Test
    fun firstLoginCreatesUserWithDefaults() =
        apiTest {
            val auth = devLogin("volunteer-1")

            assertTrue(auth.accessToken.isNotBlank())
            assertTrue(auth.refreshToken.isNotBlank())
            assertEquals(15 * 60L, auth.accessTokenExpiresIn)
            with(auth.user) {
                assertNull(role, "роль выбирается пользователем после входа")
                assertEquals("volunteer-1", displayName)
                assertEquals(listOf(Language.RU), languages)
                assertEquals(Gender.UNSPECIFIED, gender)
                assertEquals(GenderPreference.ANY, genderPreference)
                assertEquals("Europe/Moscow", timezone)
                assertEquals("22:00", doNotDisturb.from)
                assertEquals("08:00", doNotDisturb.to)
                assertTrue(notificationsEnabled)
                assertEquals("2026-09-30T10:00:00Z", createdAt)
            }
        }

    @Test
    fun repeatedLoginReturnsSameUser() =
        apiTest {
            val first = devLogin("user-a")
            val second = devLogin("user-a")
            val other = devLogin("user-b")

            assertEquals(first.user.id, second.user.id)
            assertNotEquals(first.user.id, other.user.id)
            assertNotEquals(first.refreshToken, second.refreshToken, "каждый вход — отдельная сессия")
        }

    @Test
    fun simultaneousFirstLoginsCreateOneUser() =
        apiTest {
            // Двойное нажатие «Войти»: несколько запросов с одним логином одновременно.
            val results = coroutineScope { List(5) { async { devLogin("double-tap") } }.awaitAll() }

            assertEquals(1, results.map { it.user.id }.distinct().size)
            assertEquals(1, TestDatabase.queryInt("SELECT count(*) FROM users"))
        }

    @Test
    fun invalidLoginIsRejected() =
        apiTest {
            postJson(ApiPaths.AUTH_DEV, DevLoginRequest("Not Valid!"))
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            postJson(ApiPaths.AUTH_DEV, mapOf("login" to "x", "extra" to "field"))
                .assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
        }

    @Test
    fun bannedUserCannotLogIn() =
        apiTest {
            devLogin("banned")
            TestDatabase.execute("UPDATE users SET banned_at = now()")

            postJson(ApiPaths.AUTH_DEV, DevLoginRequest("banned"))
                .assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
        }

    @Test
    fun devLoginIsAbsentWhenDisabled() =
        apiTest(testConfig(devLoginEnabled = false)) {
            val response = postJson(ApiPaths.AUTH_DEV, DevLoginRequest("volunteer-1"))

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains(ApiErrorCodes.NOT_FOUND))
        }
}
