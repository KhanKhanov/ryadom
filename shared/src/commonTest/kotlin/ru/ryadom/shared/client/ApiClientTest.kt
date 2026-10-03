package ru.ryadom.shared.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.Device
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.IncomingHelpRequests
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.SelectableRole
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.REQUEST_ID
import ru.ryadom.shared.testing.USER_ID
import ru.ryadom.shared.testing.authResponse
import ru.ryadom.shared.testing.helpRequest
import ru.ryadom.shared.testing.profile
import ru.ryadom.shared.testing.signedInStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {
    private val server = FakeServer()
    private var now = 1_000_000L

    private fun client(storage: SessionStorage = signedInStorage(now)) =
        ApiClient("http://server:8080/", server.engine, storage, now = { now })

    @Test
    fun devLoginSavesTokensAndReturnsProfile() =
        runTest {
            val storage = InMemorySessionStorage()
            server.on("POST", "/auth/dev") { FakeServer.ok(AuthResponse.serializer(), authResponse()) }

            val user = client(storage).loginDev("blind-1")

            assertEquals(USER_ID, user.id)
            assertEquals("""{"login":"blind-1"}""", server.requests.single().body)
            val session = storage.load()!!
            assertEquals("access-1", session.accessToken)
            assertEquals("refresh-1", session.refreshToken)
            assertEquals(now + 900_000, session.accessTokenExpiresAt)
        }

    @Test
    fun yandexLoginSendsOnlyTheProviderToken() =
        runTest {
            server.on("POST", "/auth/oauth/yandex") { FakeServer.ok(AuthResponse.serializer(), authResponse()) }

            client(InMemorySessionStorage()).loginYandex("yandex-token")

            // Сервер строго проверяет тело: лишние поля (даже null) были бы ошибкой в контракте.
            assertEquals("""{"accessToken":"yandex-token"}""", server.requests.single().body)
        }

    @Test
    fun authorizedRequestSendsBearerToken() =
        runTest {
            server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile()) }

            val me = client().getMe()

            assertEquals(Role.BLIND, me.role)
            assertEquals("Bearer access-0", server.requests.single().authorization)
        }

    @Test
    fun expiringAccessTokenIsRefreshedBeforeRequest() =
        runTest {
            val storage = signedInStorage(now)
            now += 900_000 - 10_000 // До истечения 10 секунд — меньше запаса в 30 секунд.
            server.on("POST", "/auth/refresh") { FakeServer.ok(AuthResponse.serializer(), authResponse("access-2", "refresh-2")) }
            server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile()) }

            client(storage).getMe()

            assertEquals("""{"refreshToken":"refresh-0"}""", server.requestsTo("POST", "/auth/refresh").single().body)
            assertEquals("Bearer access-2", server.requestsTo("GET", "/me").single().authorization)
            assertEquals("refresh-2", storage.load()!!.refreshToken)
        }

    @Test
    fun unauthorizedResponseRefreshesTokensAndRetriesOnce() =
        runTest {
            server.on("POST", "/auth/refresh") { FakeServer.ok(AuthResponse.serializer(), authResponse("access-2", "refresh-2")) }
            server.on("GET", "/me") { request ->
                if (request.authorization ==
                    "Bearer access-0"
                ) {
                    FakeServer.error(401, "unauthorized")
                } else {
                    FakeServer.ok(UserProfile.serializer(), profile())
                }
            }

            client().getMe()

            assertEquals(listOf("Bearer access-0", "Bearer access-2"), server.requestsTo("GET", "/me").map { it.authorization })
        }

    @Test
    fun concurrentRequestsRefreshTokensOnlyOnce() =
        runTest {
            val storage = signedInStorage(now)
            now += 900_000
            server.on("POST", "/auth/refresh") { FakeServer.ok(AuthResponse.serializer(), authResponse("access-2", "refresh-2")) }
            server.on("GET", "/me") { FakeServer.ok(UserProfile.serializer(), profile()) }
            val api = client(storage)

            List(3) { async { api.getMe() } }.awaitAll()

            // Refresh-токен одноразовый: второе обновление тем же токеном сервер принял бы за кражу.
            assertEquals(1, server.requestsTo("POST", "/auth/refresh").size)
        }

    @Test
    fun rejectedRefreshTokenEndsSession() =
        runTest {
            val storage = signedInStorage(now)
            now += 900_000
            server.on("POST", "/auth/refresh") { FakeServer.error(401, "invalid_refresh_token") }
            val api = client(storage)
            val ended = async(start = CoroutineStart.UNDISPATCHED) { api.sessionEnds.first() }

            val error = assertFailsWith<ApiClientException.SessionEnded> { api.getMe() }

            assertEquals(SessionEndReason.EXPIRED, error.reason)
            assertEquals(SessionEndReason.EXPIRED, ended.await())
            assertNull(storage.load())
            assertFalse(api.hasSession())
        }

    @Test
    fun bannedUserSessionEnds() =
        runTest {
            val storage = signedInStorage(now)
            server.on("POST", "/requests") { FakeServer.error(403, "user_banned") }

            val error = assertFailsWith<ApiClientException.SessionEnded> { client(storage).createRequest() }

            assertEquals(SessionEndReason.BANNED, error.reason)
            assertNull(storage.load())
        }

    @Test
    fun noSessionMeansLoggedOut() =
        runTest {
            val error = assertFailsWith<ApiClientException.SessionEnded> { client(InMemorySessionStorage()).getMe() }

            assertEquals(SessionEndReason.LOGGED_OUT, error.reason)
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun createRequestSendsEmptyObject() =
        runTest {
            server.on("POST", "/requests") { FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.SEARCHING), status = 201) }

            val request = client().createRequest()

            assertEquals(RequestStatus.SEARCHING, request.status)
            assertEquals("{}", server.requests.single().body)
        }

    @Test
    fun updateProfileSendsOnlyChangedFields() =
        runTest {
            server.on("PATCH", "/me") { FakeServer.ok(UserProfile.serializer(), profile()) }

            client().updateMe(UpdateProfileRequest(role = SelectableRole.BLIND, timezone = "Europe/Moscow"))

            assertEquals("""{"role":"blind","timezone":"Europe/Moscow"}""", server.requests.single().body)
        }

    @Test
    fun currentRequestIsNullWhenServerAnswersNoContent() =
        runTest {
            server.on("GET", "/requests/current") { FakeResponse(204) }

            assertNull(client().currentRequest())
        }

    @Test
    fun requestActionsUseRequestPaths() =
        runTest {
            server.on("GET", "/requests/$REQUEST_ID") { FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.SEARCHING)) }
            server.on("DELETE", "/requests/$REQUEST_ID") { FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.CANCELLED)) }
            server.on(
                "POST",
                "/requests/$REQUEST_ID/accept",
            ) { FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.ACCEPTED)) }
            server.on("POST", "/requests/$REQUEST_ID/rating") { FakeResponse(204) }
            val api = client()

            assertEquals(RequestStatus.SEARCHING, api.getRequest(REQUEST_ID).status)
            assertEquals(RequestStatus.CANCELLED, api.cancelRequest(REQUEST_ID).status)
            assertEquals("livekit-token", api.acceptRequest(REQUEST_ID).call?.token)
            api.rateRequest(REQUEST_ID, helped = true)

            assertEquals("""{"helped":true}""", server.requestsTo("POST", "/requests/$REQUEST_ID/rating").single().body)
        }

    @Test
    fun incomingRequestsAreUnwrapped() =
        runTest {
            server.on("GET", "/requests/incoming") {
                FakeServer.ok(IncomingHelpRequests.serializer(), IncomingHelpRequests(listOf(helpRequest(RequestStatus.SEARCHING))))
            }

            assertEquals(listOf(REQUEST_ID), client().incomingRequests().map { it.id })
        }

    @Test
    fun deviceRegistrationAndRemoval() =
        runTest {
            server.on("GET", "/push/config") { FakeServer.json(200, """{"webPushPublicKey":null}""") }
            server.on("POST", "/devices") { FakeServer.ok(Device.serializer(), Device("device-1")) }
            server.on("DELETE", "/devices/device-1") { FakeResponse(204) }
            val api = client()

            assertNull(api.pushConfig().webPushPublicKey)
            val device = api.registerDevice(RegisterDeviceRequest(PushProvider.RUSTORE, token = "rustore-token"))
            api.deleteDevice(device.id)

            // Ключей Web Push у других каналов нет — поля нет и в теле.
            assertEquals("""{"provider":"rustore","token":"rustore-token"}""", server.requestsTo("POST", "/devices").single().body)
            assertEquals("Bearer access-0", server.requestsTo("DELETE", "/devices/device-1").single().authorization)
        }

    @Test
    fun serverErrorCodeIsReported() =
        runTest {
            server.on("POST", "/requests") { FakeServer.error(409, "active_request_exists") }

            val error = assertFailsWith<ApiClientException.Server> { client().createRequest() }

            assertEquals(409, error.status)
            assertEquals("active_request_exists", error.code)
        }

    @Test
    fun unknownErrorCodeBecomesGenericMessage() =
        runTest {
            server.on("POST", "/requests") { FakeServer.error(400, "brand_new_code") }

            val error = assertFailsWith<ApiClientException.Server> { client().createRequest() }

            assertEquals(UserError.UNKNOWN, error.toUserError())
        }

    @Test
    fun proxyErrorWithoutApiBodyMeansNoConnection() =
        runTest {
            // Backend выключен, отвечает прокси перед ним.
            server.on("GET", "/me") { FakeResponse(502, "<html>Bad Gateway</html>") }

            val error = assertFailsWith<ApiClientException.Network> { client().getMe() }

            assertEquals(UserError.NETWORK, error.toUserError())
        }

    @Test
    fun networkFailureIsReportedAsNoConnection() =
        runTest {
            server.on("GET", "/me") { throw FakeNetworkFailure() }

            assertFailsWith<ApiClientException.Network> { client().getMe() }
        }

    @Test
    fun unknownFieldsInResponsesAreIgnored() =
        runTest {
            // Новая версия сервера добавила поле — старое приложение должно работать.
            val json = FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.SEARCHING)).body
            server.on("GET", "/requests/current") { FakeServer.json(200, json.replaceFirst("{", """{"newField":{"x":1},""")) }

            assertEquals(REQUEST_ID, client().currentRequest()?.id)
        }

    @Test
    fun brokenResponseIsUnexpected() =
        runTest {
            server.on("GET", "/me") { FakeServer.json(200, """{"id":1}""") }

            assertFailsWith<ApiClientException.UnexpectedResponse> { client().getMe() }
        }

    @Test
    fun logoutClearsTokensEvenWithoutConnection() =
        runTest {
            val storage = signedInStorage(now)
            server.on("POST", "/auth/logout") { throw FakeNetworkFailure() }
            val api = client(storage)
            val ended = async(start = CoroutineStart.UNDISPATCHED) { api.sessionEnds.first() }

            api.logout()

            assertNull(storage.load())
            assertEquals(SessionEndReason.LOGGED_OUT, ended.await())
            assertEquals("""{"refreshToken":"refresh-0"}""", server.requestsTo("POST", "/auth/logout").single().body)
        }

    @Test
    fun realtimeUrlFollowsServerScheme() {
        assertEquals("ws://server:8080/ws", client().realtimeUrl)
        assertEquals("wss://example.org/ws", ApiClient("https://example.org", server.engine, InMemorySessionStorage()).realtimeUrl)
    }

    @Test
    fun accessTokenForRealtimeIsRefreshedWhenTooShort() =
        runTest {
            val storage = signedInStorage(now)
            now += 900_000 - 50_000 // Осталось 50 секунд, а соединению нужна минута.
            server.on("POST", "/auth/refresh") { FakeServer.ok(AuthResponse.serializer(), authResponse("access-2", "refresh-2")) }

            val token = client(storage).accessToken(minValidityMs = 60_000)

            assertEquals("access-2", token.value)
        }

    @Test
    fun storedSessionSurvivesEncoding() {
        val session = StoredSession(USER_ID, "secret-access", 42, "secret-refresh")

        assertEquals(session, StoredSession.decode(session.encode()))
        assertNull(StoredSession.decode("garbage"))
        // Токены не должны попасть в лог или отчёт о падении.
        assertFalse(session.toString().contains("secret"))
    }

    @Test
    fun sessionEndEventsReachSubscribersStartedEarlier() =
        runTest {
            val api = client(signedInStorage(now))
            val reasons = mutableListOf<SessionEndReason>()
            val job = launch { api.sessionEnds.collect { reasons += it } }
            testScheduler.runCurrent()

            api.endSession(SessionEndReason.BANNED)
            testScheduler.runCurrent()

            assertEquals(listOf(SessionEndReason.BANNED), reasons)
            job.cancel()
        }
}
