package ru.ryadom.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JSON запросов помощи и событий WebSocket должен совпадать со схемами в `docs/api/openapi.yaml`. */
class RequestModelsSerializationTest {
    private val request =
        HelpRequest(
            id = "0199a1b2-0000-7000-8000-000000000002",
            status = RequestStatus.IN_CALL,
            language = Language.RU,
            genderPreference = GenderPreference.ANY,
            createdAt = "2026-09-30T10:00:00Z",
            acceptedAt = "2026-09-30T10:00:12Z",
            endedAt = null,
            call = CallCredentials(url = "ws://localhost:7880", room = "0199a1b2-0000-7000-8000-000000000002", token = "jwt"),
        )

    private val requestJson =
        """{"id":"0199a1b2-0000-7000-8000-000000000002","status":"in_call","language":"ru","genderPreference":"any",""" +
            """"createdAt":"2026-09-30T10:00:00Z","acceptedAt":"2026-09-30T10:00:12Z","endedAt":null,""" +
            """"call":{"url":"ws://localhost:7880","room":"0199a1b2-0000-7000-8000-000000000002","token":"jwt"}}"""

    @Test
    fun helpRequestUsesContractNamesAndExplicitNulls() {
        assertEquals(requestJson, Json.encodeToString(request))
        assertEquals(request, Json.decodeFromString<HelpRequest>(requestJson))
    }

    @Test
    fun statusesUseSnakeCase() {
        val names = RequestStatus.entries.map { Json.encodeToString(it) }

        assertEquals(
            listOf("searching", "accepted", "in_call", "ended", "no_answer", "cancelled").map { "\"$it\"" },
            names,
        )
    }

    @Test
    fun onlySearchingAcceptedAndInCallAreActive() {
        val active = RequestStatus.entries.filter { it.isActive }

        assertEquals(listOf(RequestStatus.SEARCHING, RequestStatus.ACCEPTED, RequestStatus.IN_CALL), active)
    }

    @Test
    fun createRequestAllowsEmptyBody() {
        assertEquals(CreateHelpRequest(), Json.decodeFromString<CreateHelpRequest>("{}"))
        assertEquals(
            CreateHelpRequest(language = Language.EN, genderPreference = GenderPreference.FEMALE),
            Json.decodeFromString<CreateHelpRequest>("""{"language":"en","genderPreference":"female"}"""),
        )
    }

    @Test
    fun ratingIsHelpedFlag() {
        assertEquals("""{"helped":true}""", Json.encodeToString(Rating(helped = true)))
    }

    @Test
    fun requestPathsAreBuiltFromId() {
        assertEquals("/requests/abc", ApiPaths.request("abc"))
        assertEquals("/requests/abc/accept", ApiPaths.requestAccept("abc"))
        assertEquals("/requests/abc/rating", ApiPaths.requestRating("abc"))
        assertEquals("/requests/{requestId}/accept", ApiPaths.REQUEST_ACCEPT)
    }

    @Test
    fun authMessageHasTypeField() {
        assertEquals("""{"type":"auth","accessToken":"t"}""", Realtime.encode(ClientMessage.Auth("t")))
    }

    @Test
    fun serverEventsHaveTypeField() {
        assertEquals("""{"type":"ready"}""", Realtime.encode(ServerEvent.Ready))
        assertEquals("""{"type":"request.accepted","request":$requestJson}""", Realtime.encode(ServerEvent.RequestAccepted(request)))
    }

    @Test
    fun allRequestEventTypesMatchContract() {
        val events =
            listOf(
                ServerEvent.RequestIncoming(request),
                ServerEvent.RequestAccepted(request),
                ServerEvent.RequestTaken(request),
                ServerEvent.RequestCancelled(request),
                ServerEvent.RequestNoAnswer(request),
                ServerEvent.RequestEnded(request),
            )

        val types = events.map { Realtime.encode(it).substringAfter("\"type\":\"").substringBefore('"') }

        assertEquals(
            listOf("request.incoming", "request.accepted", "request.taken", "request.cancelled", "request.no_answer", "request.ended"),
            types,
        )
        for (event in events) assertEquals(event, Realtime.decodeServerEvent(Realtime.encode(event)))
    }

    @Test
    fun clientSkipsUnknownEventsAndIgnoresNewFields() {
        assertNull(Realtime.decodeServerEvent("""{"type":"something.new"}"""))
        assertNull(Realtime.decodeServerEvent("not json"))
        assertEquals(ServerEvent.Ready, Realtime.decodeServerEvent("""{"type":"ready","serverVersion":"2"}"""))
    }

    @Test
    fun closeCodesAreInApplicationRange() {
        // Коды 4000–4999 зарезервированы для приложений (RFC 6455).
        for (code in listOf(Realtime.CLOSE_UNAUTHORIZED, Realtime.CLOSE_BANNED)) {
            assertTrue(code in 4000..4999)
        }
        assertFalse(Realtime.CLOSE_UNAUTHORIZED == Realtime.CLOSE_BANNED)
    }
}
