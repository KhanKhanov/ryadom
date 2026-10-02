package ru.ryadom.backend.users

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import ru.ryadom.backend.requests.acceptRequest
import ru.ryadom.backend.requests.cancelRequest
import ru.ryadom.backend.requests.requestHelp
import ru.ryadom.backend.testing.ApiTestScope
import ru.ryadom.backend.testing.TestDatabase
import ru.ryadom.backend.testing.apiTest
import ru.ryadom.backend.testing.assertError
import ru.ryadom.backend.testing.auth
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.ApiPaths
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.DoNotDisturb
import ru.ryadom.shared.api.Gender
import ru.ryadom.shared.api.GenderPreference
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileTest {
    private suspend fun ApiTestScope.patchMe(
        login: AuthResponse,
        body: Any,
    ): HttpResponse =
        client.patch(ApiPaths.ME) {
            auth(login)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun getMeReturnsProfile() =
        apiTest {
            val login = devLogin("anna")

            val response = client.get(ApiPaths.ME) { auth(login) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(login.user, response.body<UserProfile>())
        }

    @Test
    fun getMeRequiresAccessToken() =
        apiTest {
            val response = client.get(ApiPaths.ME)

            response.assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            client
                .get(ApiPaths.ME) { header(HttpHeaders.Authorization, "Bearer not-a-jwt") }
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun accessTokenExpiresAfter15Minutes() =
        apiTest {
            val login = devLogin()
            clock.advance(Duration.ofMinutes(14))
            assertEquals(HttpStatusCode.OK, client.get(ApiPaths.ME) { auth(login) }.status)

            clock.advance(Duration.ofMinutes(2))

            client.get(ApiPaths.ME) { auth(login) }.assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun patchUpdatesOnlyGivenFields() =
        apiTest {
            val login = devLogin("anna")

            val response =
                patchMe(
                    login,
                    UpdateProfileRequest(
                        role = SelectableRole.VOLUNTEER,
                        displayName = "  Анна  ",
                        languages = listOf(Language.RU, Language.EN, Language.RU),
                        gender = Gender.FEMALE,
                        timezone = "Asia/Yekaterinburg",
                        doNotDisturb = DoNotDisturb("23:30", "07:00"),
                        notificationsEnabled = false,
                    ),
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val expected =
                login.user.copy(
                    role = Role.VOLUNTEER,
                    displayName = "Анна",
                    languages = listOf(Language.RU, Language.EN),
                    gender = Gender.FEMALE,
                    timezone = "Asia/Yekaterinburg",
                    doNotDisturb = DoNotDisturb("23:30", "07:00"),
                    notificationsEnabled = false,
                )
            assertEquals(expected, response.body<UserProfile>())
            assertEquals(expected, client.get(ApiPaths.ME) { auth(login) }.body<UserProfile>(), "изменения сохранены в базе")

            val afterSecondPatch = patchMe(login, UpdateProfileRequest(genderPreference = GenderPreference.FEMALE)).body<UserProfile>()
            assertEquals(expected.copy(genderPreference = GenderPreference.FEMALE), afterSecondPatch)
        }

    @Test
    fun genderCanBeReset() =
        apiTest {
            val login = devLogin()
            patchMe(login, UpdateProfileRequest(gender = Gender.MALE, genderPreference = GenderPreference.MALE))

            val profile =
                patchMe(login, UpdateProfileRequest(gender = Gender.UNSPECIFIED, genderPreference = GenderPreference.ANY))
                    .body<UserProfile>()

            assertEquals(Gender.UNSPECIFIED, profile.gender)
            assertEquals(GenderPreference.ANY, profile.genderPreference)
        }

    @Test
    fun emptyPatchChangesNothing() =
        apiTest {
            val login = devLogin()

            val response = patchMe(login, UpdateProfileRequest())

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(login.user, response.body<UserProfile>())
        }

    @Test
    fun invalidValuesAreRejected() =
        apiTest {
            val login = devLogin()
            val invalid =
                listOf(
                    UpdateProfileRequest(displayName = "   "),
                    UpdateProfileRequest(displayName = "Я".repeat(51)),
                    UpdateProfileRequest(displayName = "Анна\nИванова"),
                    UpdateProfileRequest(languages = emptyList()),
                    UpdateProfileRequest(timezone = "Mars/Olympus"),
                    UpdateProfileRequest(timezone = "+03:00"),
                    UpdateProfileRequest(doNotDisturb = DoNotDisturb("24:00", "08:00")),
                    UpdateProfileRequest(doNotDisturb = DoNotDisturb("22:00", "8:00")),
                )
            for (request in invalid) {
                patchMe(login, request).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            }
            patchMe(login, mapOf("role" to "admin")).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            patchMe(login, mapOf("unknownField" to "x")).assertError(HttpStatusCode.BadRequest, ApiErrorCodes.INVALID_REQUEST)
            assertEquals(login.user, client.get(ApiPaths.ME) { auth(login) }.body<UserProfile>(), "профиль не изменился")
        }

    @Test
    fun adminCannotChangeOwnRole() =
        apiTest {
            val login = devLogin("admin")
            TestDatabase.execute("UPDATE users SET role = 'admin'")

            patchMe(login, UpdateProfileRequest(role = SelectableRole.BLIND))
                .assertError(HttpStatusCode.Forbidden, ApiErrorCodes.FORBIDDEN)
            assertEquals(HttpStatusCode.OK, patchMe(login, UpdateProfileRequest(displayName = "Модератор")).status)
        }

    @Test
    fun roleCannotChangeDuringHelpRequestOrCall() =
        apiTest {
            val blind = blind()
            val volunteer = volunteer("volunteer-1")
            connect(volunteer)
            val request = requestHelp(blind)
            patchMe(blind, UpdateProfileRequest(role = SelectableRole.VOLUNTEER))
                .assertError(HttpStatusCode.Conflict, ApiErrorCodes.ACTIVE_REQUEST_EXISTS)

            acceptRequest(volunteer, request.id)

            patchMe(blind, UpdateProfileRequest(role = SelectableRole.VOLUNTEER))
                .assertError(HttpStatusCode.Conflict, ApiErrorCodes.ACTIVE_REQUEST_EXISTS)
            patchMe(volunteer, UpdateProfileRequest(role = SelectableRole.BLIND))
                .assertError(HttpStatusCode.Conflict, ApiErrorCodes.ACTIVE_REQUEST_EXISTS)
            // Остальные поля и та же роль — можно.
            val sameRole = patchMe(blind, UpdateProfileRequest(role = SelectableRole.BLIND, displayName = "Анна"))
            assertEquals(HttpStatusCode.OK, sameRole.status)

            cancelRequest(blind, request.id)

            assertEquals(HttpStatusCode.OK, patchMe(blind, UpdateProfileRequest(role = SelectableRole.VOLUNTEER)).status)
            assertEquals(HttpStatusCode.OK, patchMe(volunteer, UpdateProfileRequest(role = SelectableRole.BLIND)).status)
        }

    @Test
    fun bannedUserGetsForbidden() =
        apiTest {
            val login = devLogin()
            TestDatabase.execute("UPDATE users SET banned_at = now()")

            client.get(ApiPaths.ME) { auth(login) }.assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
            patchMe(login, UpdateProfileRequest(displayName = "x"))
                .assertError(HttpStatusCode.Forbidden, ApiErrorCodes.USER_BANNED)
        }

    @Test
    fun deletedUserTokenIsUnauthorized() =
        apiTest {
            val login = devLogin()
            TestDatabase.execute("DELETE FROM users")

            client.get(ApiPaths.ME) { auth(login) }.assertError(HttpStatusCode.Unauthorized, ApiErrorCodes.UNAUTHORIZED)
        }
}
