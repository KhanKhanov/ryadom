package ru.ryadom.shared.volunteer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.IncomingHelpRequests
import ru.ryadom.shared.api.Rating
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.testing.FakeCalls
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.FakeTransport
import ru.ryadom.shared.testing.NoJitter
import ru.ryadom.shared.testing.OTHER_REQUEST_ID
import ru.ryadom.shared.testing.REQUEST_ID
import ru.ryadom.shared.testing.callCredentials
import ru.ryadom.shared.testing.helpRequest
import ru.ryadom.shared.testing.profile
import ru.ryadom.shared.testing.signedInStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolunteerControllerTest {
    private class Setup(
        private val scope: TestScope,
    ) {
        val server = FakeServer(StandardTestDispatcher(scope.testScheduler))
        val api = ApiClient("http://server", server.engine, signedInStorage(), now = { scope.testScheduler.currentTime })
        val transport = FakeTransport()
        val realtime = RealtimeConnection(api.realtimeUrl, api, transport, now = { scope.testScheduler.currentTime }, random = NoJitter)
        val calls = FakeCalls()
        val profiles = mutableListOf<UserProfile>()
        val controller = VolunteerController(api, realtime, calls, scope.backgroundScope, onProfileChanged = { profiles += it })
        val waiting = mutableListOf<Set<String>>()

        val state get() = controller.state.value

        init {
            respondCurrent(null)
            respondIncoming()
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.waitingSynced.collect { waiting += it } }
        }

        fun respondCurrent(request: HelpRequest?) {
            server.on("GET", "/requests/current") {
                if (request == null) FakeResponse(204) else FakeServer.ok(HelpRequest.serializer(), request)
            }
        }

        fun respondIncoming(vararg requests: HelpRequest) {
            server.on(
                "GET",
                "/requests/incoming",
            ) { FakeServer.ok(IncomingHelpRequests.serializer(), IncomingHelpRequests(requests.toList())) }
        }

        fun respond(
            method: String,
            path: String,
            request: HelpRequest,
        ) = server.on(method, path) { FakeServer.ok(HelpRequest.serializer(), request) }

        /** Запуск и подключение к серверу событий. */
        fun start() {
            controller.start()
            scope.runCurrent()
            transport.last.receive(ServerEvent.Ready)
            scope.runCurrent()
        }

        fun event(event: ServerEvent) {
            transport.last.receive(event)
            scope.runCurrent()
        }

        /** Вызов пришёл и принят; звонок подключился. */
        fun acceptCall(peerJoined: Boolean) {
            start()
            event(ServerEvent.RequestIncoming(helpRequest(RequestStatus.SEARCHING)))
            respond("POST", "/requests/$REQUEST_ID/accept", helpRequest(RequestStatus.ACCEPTED))
            controller.accept(REQUEST_ID)
            scope.runCurrent()
            if (peerJoined) report(PeerPresence.PRESENT)
        }

        fun report(peer: PeerPresence) {
            calls.last.report(CallState(CallConnection.CONNECTED, peer, MicrophoneState.ON))
            scope.runCurrent()
        }
    }

    @Test
    fun startLoadsWaitingCalls() =
        runTest {
            val s = Setup(this)
            s.respondIncoming(helpRequest(RequestStatus.SEARCHING))
            assertFalse(s.state.synced)

            s.start()

            assertTrue(s.state.synced)
            assertEquals(listOf(REQUEST_ID), s.state.incoming.map { it.id })
            assertTrue(s.state.ringing)
            assertEquals(RealtimeStatus.CONNECTED, s.state.connection)
            // Сверка — при запуске и после `ready`.
            assertEquals(setOf(REQUEST_ID), s.waiting.last())
        }

    @Test
    fun incomingEventRings() =
        runTest {
            val s = Setup(this)
            s.start()

            s.event(ServerEvent.RequestIncoming(helpRequest(RequestStatus.SEARCHING)))

            assertTrue(s.state.ringing)
            assertEquals(VolunteerNotice.INCOMING, s.state.notice?.kind)
        }

    @Test
    fun acceptStartsAudioOnlyCall() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = false)

            assertEquals(VolunteerScreen.CALL, s.state.screen)
            assertEquals(1, s.server.requestsTo("POST", "/requests/$REQUEST_ID/accept").size)
            assertEquals(callCredentials, s.calls.last.credentials)
            // Токен LiveKit разрешает волонтёру только микрофон.
            assertEquals(CallOptions(publishCamera = false), s.calls.last.options)
            assertTrue(s.calls.last.connected)
            assertFalse(s.state.ringing)
        }

    @Test
    fun acceptWorksForCallKnownOnlyFromPush() =
        runTest {
            val s = Setup(this)
            s.start()
            s.respond("POST", "/requests/$OTHER_REQUEST_ID/accept", helpRequest(RequestStatus.ACCEPTED, id = OTHER_REQUEST_ID))

            s.controller.accept(OTHER_REQUEST_ID)
            runCurrent()

            assertEquals(OTHER_REQUEST_ID, s.state.call?.requestId)
        }

    @Test
    fun acceptTooLateRemovesTheCall() =
        runTest {
            val s = Setup(this)
            s.start()
            s.event(ServerEvent.RequestIncoming(helpRequest(RequestStatus.SEARCHING)))
            s.server.on("POST", "/requests/$REQUEST_ID/accept") { FakeServer.error(409, "request_taken") }

            s.controller.accept(REQUEST_ID)
            assertEquals(REQUEST_ID, s.state.accepting)
            runCurrent()

            assertEquals(emptyList(), s.state.incoming)
            assertEquals(VolunteerNotice.TOO_LATE_TAKEN, s.state.notice?.kind)
            assertTrue(s.calls.created.isEmpty())
        }

    @Test
    fun acceptWithoutConnectionCanBeRetried() =
        runTest {
            val s = Setup(this)
            s.start()
            s.event(ServerEvent.RequestIncoming(helpRequest(RequestStatus.SEARCHING)))
            s.server.on("POST", "/requests/$REQUEST_ID/accept") { throw FakeNetworkFailure() }

            s.controller.accept(REQUEST_ID)
            runCurrent()

            assertEquals(1, s.state.incoming.size)
            assertNull(s.state.accepting)
            assertEquals(UserError.NETWORK, s.state.error)
        }

    @Test
    fun callAcceptedElsewhereIsShownInstead() =
        runTest {
            val s = Setup(this)
            s.start()
            s.event(ServerEvent.RequestIncoming(helpRequest(RequestStatus.SEARCHING, id = OTHER_REQUEST_ID)))
            s.server.on("POST", "/requests/$OTHER_REQUEST_ID/accept") { FakeServer.error(409, "active_request_exists") }
            s.respondCurrent(helpRequest(RequestStatus.IN_CALL))

            s.controller.accept(OTHER_REQUEST_ID)
            runCurrent()

            assertEquals(REQUEST_ID, s.state.call?.requestId)
            assertEquals(VolunteerNotice.ALREADY_IN_CALL, s.state.notice?.kind)
        }

    @Test
    fun callIsRestoredAfterRestart() =
        runTest {
            val s = Setup(this)
            s.respondCurrent(helpRequest(RequestStatus.IN_CALL))

            s.start()

            assertEquals(REQUEST_ID, s.state.call?.requestId)
            assertEquals(1, s.calls.created.size)
        }

    @Test
    fun peerEndingTheCallAsksForRating() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)

            s.event(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))

            assertEquals(VolunteerScreen.RATING, s.state.screen)
            assertTrue(s.calls.last.disconnected)

            s.server.on("POST", "/requests/$REQUEST_ID/rating") { FakeResponse(204) }
            s.controller.rate(helped = true)
            runCurrent()

            assertEquals(
                Rating(helped = true),
                FakeServer.decode(Rating.serializer(), s.server.requestsTo("POST", "/requests/$REQUEST_ID/rating").single()),
            )
            assertEquals(VolunteerScreen.HOME, s.state.screen)
            assertEquals(VolunteerNotice.RATED, s.state.notice?.kind)
        }

    @Test
    fun endingTheCallDisconnectsAtOnceAndRetriesWithoutConnection() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)
            s.server.on("DELETE", "/requests/$REQUEST_ID") { throw FakeNetworkFailure() }

            s.controller.endCall()
            runCurrent()

            assertTrue(s.calls.last.disconnected)
            assertEquals(true, s.state.call?.ending)
            assertEquals(UserError.NETWORK, s.state.error)

            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ENDED))
            advanceTimeBy(2_001)

            assertEquals(FinishedCall(REQUEST_ID, endedByPeer = false), s.state.finished)
            assertNull(s.state.error)
            assertEquals(2, s.server.requestsTo("DELETE", "/requests/$REQUEST_ID").size)
        }

    @Test
    fun serverRefusingToEndTheCallStillEndsItHere() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = false)
            s.server.on("DELETE", "/requests/$REQUEST_ID") { FakeServer.error(404, "not_found") }

            s.controller.endCall()
            runCurrent()

            assertNull(s.state.call)
            assertEquals(VolunteerNotice.CALL_ENDED, s.state.notice?.kind)
        }

    @Test
    fun readySwitchUpdatesTheProfile() =
        runTest {
            val s = Setup(this)
            s.start()
            val updated = profile(Role.VOLUNTEER).copy(notificationsEnabled = false)
            s.server.on("PATCH", "/me") { FakeServer.ok(UserProfile.serializer(), updated) }

            s.controller.setReady(false)
            assertTrue(s.state.busy)
            runCurrent()

            assertEquals(
                UpdateProfileRequest(notificationsEnabled = false),
                FakeServer.decode(UpdateProfileRequest.serializer(), s.server.requestsTo("PATCH", "/me").single()),
            )
            assertEquals(listOf(updated), s.profiles)
            assertEquals(VolunteerNotice.READY_OFF, s.state.notice?.kind)
            assertFalse(s.state.busy)
        }

    @Test
    fun readySwitchErrorIsShown() =
        runTest {
            val s = Setup(this)
            s.start()
            s.server.on("PATCH", "/me") { throw FakeNetworkFailure() }

            s.controller.setReady(true)
            runCurrent()

            assertEquals(UserError.NETWORK, s.state.error)
            assertEquals(emptyList(), s.profiles)
        }

    @Test
    fun pushRefreshFindsCallsMissedWithoutConnection() =
        runTest {
            val s = Setup(this)
            s.start()
            s.respondIncoming(helpRequest(RequestStatus.SEARCHING))

            s.controller.refresh()
            runCurrent()

            assertEquals(listOf(REQUEST_ID), s.state.incoming.map { it.id })
            assertEquals(setOf(REQUEST_ID), s.waiting.last())
        }

    @Test
    fun skippedCallsAreNotWaitingAnyMore() =
        runTest {
            val s = Setup(this)
            s.respondIncoming(helpRequest(RequestStatus.SEARCHING), helpRequest(RequestStatus.SEARCHING, id = OTHER_REQUEST_ID))
            s.start()

            s.controller.skip(REQUEST_ID)
            s.controller.refresh()
            runCurrent()

            assertEquals(listOf(OTHER_REQUEST_ID), s.state.incoming.map { it.id })
            assertEquals(setOf(OTHER_REQUEST_ID), s.waiting.last())
        }

    @Test
    fun callClosedWhileOfflineIsClosedAfterReconnect() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)
            s.transport.last.drop()
            runCurrent()
            s.respondCurrent(null)
            s.respond("GET", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ENDED))

            advanceTimeBy(1_001)
            s.event(ServerEvent.Ready)

            assertEquals(FinishedCall(REQUEST_ID, endedByPeer = true), s.state.finished)
        }

    @Test
    fun logoutDuringCallEndsItFirst() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)
            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ENDED))
            s.server.on("POST", "/auth/logout") { FakeResponse(204) }

            s.api.logout()

            val order = s.server.requests.map { "${it.method} ${it.path}" }
            assertTrue(order.indexOf("DELETE /requests/$REQUEST_ID") in 0 until order.indexOf("POST /auth/logout"), order.toString())
        }

    @Test
    fun stopDisconnectsAndForgetsTheLogoutTask() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)

            s.controller.stop()
            s.server.on("POST", "/auth/logout") { FakeResponse(204) }
            s.api.logout()

            assertTrue(s.calls.last.disconnected)
            assertTrue(s.transport.last.closedByClient)
            assertEquals(emptyList(), s.server.requestsTo("DELETE", "/requests/$REQUEST_ID"))
        }

    @Test
    fun microphoneSwitchAndBlockedMicrophoneRetryReachTheCall() =
        runTest {
            val s = Setup(this)
            s.acceptCall(peerJoined = true)

            s.controller.setMicrophoneEnabled(false)
            s.controller.retryBlockedDevices()
            assertEquals(false, s.calls.last.microphone)
            assertEquals(0, s.calls.last.retries)

            s.calls.last.report(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, MicrophoneState.BLOCKED))
            runCurrent()
            s.controller.retryBlockedDevices()
            assertEquals(1, s.calls.last.retries)
        }
}
