package ru.ryadom.shared.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JSON моделей должен совпадать со схемами в `docs/api/openapi.yaml`. */
class ApiModelsSerializationTest {
    private val profile =
        UserProfile(
            id = "0199a1b2-0000-7000-8000-000000000001",
            role = null,
            displayName = null,
            languages = listOf(Language.RU, Language.EN),
            gender = Gender.UNSPECIFIED,
            genderPreference = GenderPreference.ANY,
            timezone = "Europe/Moscow",
            doNotDisturb = DoNotDisturb(from = "22:00", to = "08:00"),
            notificationsEnabled = true,
            createdAt = "2026-09-30T10:15:30Z",
        )

    @Test
    fun profileUsesContractNamesAndExplicitNulls() {
        val json = Json.encodeToString(profile)

        assertEquals(
            """{"id":"0199a1b2-0000-7000-8000-000000000001","role":null,"displayName":null,""" +
                """"languages":["ru","en"],"gender":"unspecified","genderPreference":"any",""" +
                """"timezone":"Europe/Moscow","doNotDisturb":{"from":"22:00","to":"08:00"},""" +
                """"notificationsEnabled":true,"createdAt":"2026-09-30T10:15:30Z"}""",
            json,
        )
    }

    @Test
    fun profileRoundTrips() {
        val withRole = profile.copy(role = Role.VOLUNTEER, displayName = "Анна")

        assertEquals(withRole, Json.decodeFromString<UserProfile>(Json.encodeToString(withRole)))
    }

    @Test
    fun updateRequestAllowsPartialBody() {
        val request = Json.decodeFromString<UpdateProfileRequest>("""{"role":"blind","doNotDisturb":{"from":"23:00","to":"07:30"}}""")

        assertEquals(SelectableRole.BLIND, request.role)
        assertEquals(Role.BLIND, request.role?.role)
        assertEquals(DoNotDisturb("23:00", "07:30"), request.doNotDisturb)
        assertNull(request.displayName)
    }

    @Test
    fun adminCannotBeSelectedAsRole() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<UpdateProfileRequest>("""{"role":"admin"}""")
        }
    }

    @Test
    fun oauthRequestFieldsAreOptional() {
        val request = Json.decodeFromString<OAuthLoginRequest>("""{"accessToken":"t"}""")

        assertEquals(OAuthLoginRequest(accessToken = "t"), request)
    }

    @Test
    fun oauthProviderMatchesPathValue() {
        assertEquals(OAuthProvider.YANDEX, OAuthProvider.fromPathValue("yandex"))
        assertNull(OAuthProvider.fromPathValue("vk"))
        assertEquals("/auth/oauth/yandex", ApiPaths.authOAuth(OAuthProvider.YANDEX))
    }

    @Test
    fun devLoginRegexAcceptsOnlySimpleLogins() {
        assertTrue(DevLoginRequest.LOGIN_REGEX.matches("volunteer-1"))
        assertFalse(DevLoginRequest.LOGIN_REGEX.matches("Volunteer"))
        assertFalse(DevLoginRequest.LOGIN_REGEX.matches(""))
        assertFalse(DevLoginRequest.LOGIN_REGEX.matches("a".repeat(33)))
    }

    @Test
    fun doNotDisturbTimeRegexAcceptsOnlyValidTime() {
        assertTrue(DoNotDisturb.TIME_REGEX.matches("00:00"))
        assertTrue(DoNotDisturb.TIME_REGEX.matches("23:59"))
        assertFalse(DoNotDisturb.TIME_REGEX.matches("24:00"))
        assertFalse(DoNotDisturb.TIME_REGEX.matches("7:30"))
    }
}
