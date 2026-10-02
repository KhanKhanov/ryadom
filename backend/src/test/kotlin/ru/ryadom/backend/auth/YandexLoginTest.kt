package ru.ryadom.backend.auth

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import ru.ryadom.backend.testing.FakeYandex
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.testConfig
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.OAuthLoginRequest
import ru.ryadom.shared.api.OAuthProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class YandexLoginTest {
    private val path = ApiPaths.authOAuth(OAuthProvider.YANDEX)

    @Test
    fun mobileTokenLoginCreatesUserWithFirstName() =
        apiTest {
            yandex.addUser("mobile-token", FakeYandex.UserInfo(id = "1001", firstName = "Анна"))

            val response = postJson(path, OAuthLoginRequest(accessToken = "mobile-token"))

            assertEquals(HttpStatusCode.OK, response.status)
            val auth = response.body<AuthResponse>()
            assertEquals("Анна", auth.user.displayName)
            assertNull(auth.user.role)
        }

    @Test
    fun sameYandexUserGetsSameAccount() =
        apiTest {
            yandex.addUser("token-1", FakeYandex.UserInfo(id = "1001"))
            yandex.addUser("token-2", FakeYandex.UserInfo(id = "1001"))
            yandex.addUser("token-3", FakeYandex.UserInfo(id = "2002"))

            val first = postJson(path, OAuthLoginRequest(accessToken = "token-1")).body<AuthResponse>()
            val second = postJson(path, OAuthLoginRequest(accessToken = "token-2")).body<AuthResponse>()
            val other = postJson(path, OAuthLoginRequest(accessToken = "token-3")).body<AuthResponse>()

            assertEquals(first.user.id, second.user.id)
            assertNotEquals(first.user.id, other.user.id)
            assertNull(first.user.displayName, "без доступа к имени имя не задано")
        }

    @Test
    fun yandexAndDevUsersWithSameSubjectAreDifferent() =
        apiTest {
            yandex.addUser("token", FakeYandex.UserInfo(id = "user-1"))

            val viaYandex = postJson(path, OAuthLoginRequest(accessToken = "token")).body<AuthResponse>()
            val viaDev = devLogin("user-1")

            assertNotEquals(viaYandex.user.id, viaDev.user.id)
        }

    @Test
    fun tokenOfTrustedAndroidClientIsAccepted() =
        apiTest {
            yandex.addUser("android-token", FakeYandex.UserInfo(id = "1001", clientId = "android-client-id"))

            assertEquals(HttpStatusCode.OK, postJson(path, OAuthLoginRequest(accessToken = "android-token")).status)
        }

    @Test
    fun tokenIssuedForAnotherAppIsRejected() =
        apiTest {
            // Любой сайт с входом через Яндекс может получить токен пользователя. Такой токен не должен пускать к нам.
            yandex.addUser("foreign-token", FakeYandex.UserInfo(id = "1001", clientId = "someone-elses-app"))

            postJson(path, OAuthLoginRequest(accessToken = "foreign-token"))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.OAUTH_FAILED)
        }

    @Test
    fun invalidTokenIsRejected() =
        apiTest {
            postJson(path, OAuthLoginRequest(accessToken = "unknown"))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.OAUTH_FAILED)
        }

    @Test
    fun webCodeIsExchangedWithPkceVerifier() =
        apiTest {
            val verifier = "v".repeat(43)
            yandex.codes["web-code"] = "web-token"
            yandex.addUser("web-token", FakeYandex.UserInfo(id = "3003", firstName = "Иван"))

            val response = postJson(path, OAuthLoginRequest(code = "web-code", codeVerifier = verifier))

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Иван", response.body<AuthResponse>().user.displayName)
            val form = yandex.tokenRequests.single()
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("web-code", form["code"])
            assertEquals(FakeYandex.CLIENT_ID, form["client_id"])
            assertEquals("test-client-secret", form["client_secret"])
            assertEquals(verifier, form["code_verifier"])
        }

    @Test
    fun expiredCodeIsRejected() =
        apiTest {
            postJson(path, OAuthLoginRequest(code = "expired"))
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.OAUTH_FAILED)
        }

    @Test
    fun wrongServerCredentialsLookLikeProviderFailure() =
        apiTest(testConfig(yandex = FakeYandex.config.copy(clientId = "misconfigured"))) {
            postJson(path, OAuthLoginRequest(code = "web-code"))
                .assertError(HttpStatusCode.BadGateway, ApiErrorCodes.PROVIDER_UNAVAILABLE)
        }

    @Test
    fun yandexOutageIsReportedAsProviderUnavailable() =
        apiTest {
            yandex.failWith = HttpStatusCode.ServiceUnavailable

            postJson(path, OAuthLoginRequest(accessToken = "token"))
                .assertError(HttpStatusCode.BadGateway, ApiErrorCodes.PROVIDER_UNAVAILABLE)
        }

    @Test
    fun longFirstNameIsTruncated() =
        apiTest {
            yandex.addUser("token", FakeYandex.UserInfo(id = "1001", firstName = "  " + "Я".repeat(80)))

            val auth = postJson(path, OAuthLoginRequest(accessToken = "token")).body<AuthResponse>()

            assertEquals("Я".repeat(50), auth.user.displayName)
        }

    @Test
    fun requestMustContainExactlyOneCredential() =
        apiTest {
            val invalid =
                listOf(
                    OAuthLoginRequest(),
                    OAuthLoginRequest(code = "c", accessToken = "t"),
                    OAuthLoginRequest(accessToken = "t", codeVerifier = "v".repeat(43)),
                    OAuthLoginRequest(code = "c", codeVerifier = "too-short"),
                    OAuthLoginRequest(accessToken = "   "),
                )
            for (request in invalid) {
                postJson(path, request).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            }
        }

    @Test
    fun unknownProviderIsNotFound() =
        apiTest {
            postJson("/auth/oauth/vk", OAuthLoginRequest(accessToken = "t"))
                .assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun yandexIsNotFoundWhenNotConfigured() =
        apiTest(testConfig(yandex = null)) {
            postJson(path, OAuthLoginRequest(accessToken = "t"))
                .assertError(HttpStatusCode.NotFound, ApiErrorCodes.NOT_FOUND)
        }
}
