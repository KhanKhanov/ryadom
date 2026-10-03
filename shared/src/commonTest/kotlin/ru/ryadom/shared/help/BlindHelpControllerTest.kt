package ru.ryadom.shared.help

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallFactory
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallSession
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CallStatus
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.call.status
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.FakeTransport
import ru.ryadom.shared.testing.REQUEST_ID
import ru.ryadom.shared.testing.callCredentials
import ru.ryadom.shared.testing.helpRequest
import ru.ryadom.shared.testing.signedInStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlindHelpControllerTest {
    /** Поддельный звонок: запоминает, что с ним сделали; [report] — сообщить новое состояние, как LiveKit. */
    private class FakeCall(
        val credentials: CallCredentials,
        val options: CallOptions,
        val report: (CallState) -> Unit,
    ) : CallSession {
        var connected = false
        var disconnected = false
        var microphone: Boolean? = null
        var retries = 0

        override fun connect() {
            connected = true
        }

        override fun setMicrophoneEnabled(enabled: Boolean) {
            microphone = enabled
        }

        override fun retryBlockedDevices() {
            retries++
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    private class Setup(
        private val scope: TestScope,
        hour: Int = 12,
    ) {
        val server = FakeServer(StandardTestDispatcher(scope.testScheduler))
        val api = ApiClient("http://server", server.engine, signedInStorage(), now = { scope.testScheduler.currentTime })
        val transport = FakeTransport()
        val realtime = RealtimeConnection(api.realtimeUrl, api, transport, now = { scope.testScheduler.currentTime })
        val calls = mutableListOf<FakeCall>()
        val controller =
            BlindHelpController(
                api,
                realtime,
                CallFactory { credentials, options, report -> FakeCall(credentials, options, report).also { calls += it } },
                scope.backgroundScope,
                localHour = { hour },
            )

        val state get() = controller.state.value
        val screen get() = state.screen
        val call get() = calls.last()

        init {
            respondCurrent(null)
        }

        fun respondCurrent(request: HelpRequest?) {
            server.on("GET", "/requests/current") {
                if (request ==
                    null
                ) {
                    FakeResponse(204)
                } else {
                    FakeServer.ok(HelpRequest.serializer(), request)
                }
            }
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

        /** Довести до звонка с волонтёром. */
        fun startCall(volunteerJoined: Boolean) {
            respondCurrent(helpRequest(RequestStatus.IN_CALL))
            start()
            if (volunteerJoined) reportCall(PeerPresence.PRESENT)
        }

        fun reportCall(peer: PeerPresence) {
            call.report(CallState(connection = CallConnection.CONNECTED, peer = peer))
            scope.runCurrent()
        }
    }

    @Test
    fun withoutActiveRequestShowsTheButton() =
        runTest {
            val s = Setup(this)
            s.start()

            assertEquals(BlindScreen.Ready(), s.screen)
            assertEquals(RealtimeStatus.CONNECTED, s.state.connection)
        }

    @Test
    fun serverUnavailableAtStartStillShowsTheButton() =
        runTest {
            val s = Setup(this)
            s.server.on("GET", "/requests/current") { throw FakeNetworkFailure() }

            s.controller.start()
            runCurrent()

            assertEquals(BlindScreen.Ready(), s.screen)
        }

    @Test
    fun restoresSearchAfterRestart() =
        runTest {
            val s = Setup(this)
            s.respondCurrent(helpRequest(RequestStatus.SEARCHING))

            s.start()

            assertEquals(BlindScreen.Searching(REQUEST_ID), s.screen)
        }

    @Test
    fun restoresCallAfterRestartAndConnects() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = false)

            assertEquals(BlindScreen.Call(REQUEST_ID, callCredentials), s.screen)
            assertEquals(1, s.calls.size)
            assertTrue(s.call.connected)
        }

    @Test
    fun requestThenAcceptanceStartsCallWithCamera() =
        runTest {
            val s = Setup(this)
            s.respond("POST", "/requests", helpRequest(RequestStatus.SEARCHING))
            s.start()

            s.controller.requestHelp()
            assertTrue(s.state.busy)
            runCurrent()
            assertEquals(BlindScreen.Searching(REQUEST_ID), s.screen)
            assertFalse(s.state.busy)

            s.event(ServerEvent.RequestAccepted(helpRequest(RequestStatus.ACCEPTED)))

            assertEquals(BlindScreen.Call(REQUEST_ID, callCredentials), s.screen)
            assertEquals(callCredentials, s.call.credentials)
            assertTrue(s.call.options.publishCamera)
            assertTrue(s.call.connected)
        }

    @Test
    fun callWithVolunteerEndsWithRating() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)

            s.event(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))

            assertEquals(BlindScreen.Rating(REQUEST_ID), s.screen)
            assertTrue(s.call.disconnected)
        }

    @Test
    fun volunteerEndingBeforeJoiningOffersToAskAgain() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = false)

            s.event(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))

            assertEquals(BlindScreen.Ready(HelpOutcome.VOLUNTEER_LEFT), s.screen)
            assertTrue(s.call.disconnected)
        }

    @Test
    fun volunteerDisappearingFromCallIsReported() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)

            s.reportCall(PeerPresence.LEFT)

            assertEquals(CallStatus.PEER_LEFT, (s.screen as BlindScreen.Call).call.status)
            assertFalse(s.call.disconnected)
        }

    @Test
    fun noAnswerAtNightHasItsOwnText() =
        runTest {
            val s = Setup(this, hour = 23)
            s.respondCurrent(helpRequest(RequestStatus.SEARCHING))
            s.start()

            s.event(ServerEvent.RequestNoAnswer(helpRequest(RequestStatus.NO_ANSWER)))

            assertEquals(BlindScreen.Ready(HelpOutcome.NO_ANSWER_AT_NIGHT), s.screen)
        }

    @Test
    fun searchCanBeCancelled() =
        runTest {
            val s = Setup(this)
            s.respondCurrent(helpRequest(RequestStatus.SEARCHING))
            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.CANCELLED))
            s.start()

            s.controller.cancelSearch()
            runCurrent()

            assertEquals(BlindScreen.Ready(HelpOutcome.CANCELLED), s.screen)
        }

    @Test
    fun endingCallStopsCameraAtOnceAndAsksForRating() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)
            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ENDED))

            s.controller.endCall()

            // Камера и микрофон выключаются сразу, не дожидаясь сервера.
            assertTrue(s.call.disconnected)
            assertTrue((s.screen as BlindScreen.Call).ending)
            runCurrent()
            assertEquals(BlindScreen.Rating(REQUEST_ID), s.screen)
        }

    @Test
    fun endingCallWithoutConnectionIsRetried() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = false)
            s.server.on("DELETE", "/requests/$REQUEST_ID") { throw FakeNetworkFailure() }

            s.controller.endCall()
            runCurrent()
            assertEquals(UserError.NETWORK, s.state.error)
            assertTrue((s.screen as BlindScreen.Call).ending)

            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.CANCELLED))
            advanceTimeBy(2_001)

            assertEquals(BlindScreen.Ready(HelpOutcome.CALL_ENDED), s.screen)
            assertNull(s.state.error)
        }

    @Test
    fun ratingIsSentAndThanked() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)
            s.event(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))
            s.server.on("POST", "/requests/$REQUEST_ID/rating") { FakeResponse(204) }

            s.controller.rate(helped = true)
            runCurrent()

            assertEquals(BlindScreen.Ready(HelpOutcome.RATED), s.screen)
            assertEquals(
                """{"helped":true}""",
                s.server
                    .requestsTo("POST", "/requests/$REQUEST_ID/rating")
                    .single()
                    .body,
            )
        }

    @Test
    fun ratingCanBeSkipped() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)
            s.event(ServerEvent.RequestEnded(helpRequest(RequestStatus.ENDED)))

            s.controller.skipRating()

            assertEquals(BlindScreen.Ready(), s.screen)
            assertTrue(s.server.requestsTo("POST", "/requests/$REQUEST_ID/rating").isEmpty())
        }

    @Test
    fun requestWithoutConnectionShowsError() =
        runTest {
            val s = Setup(this)
            s.server.on("POST", "/requests") { throw FakeNetworkFailure() }
            s.start()

            s.controller.requestHelp()
            runCurrent()

            assertEquals(BlindScreen.Ready(), s.screen)
            assertEquals(UserError.NETWORK, s.state.error)
            assertFalse(s.state.busy)
        }

    @Test
    fun tooManyRequestsIsExplained() =
        runTest {
            val s = Setup(this)
            s.server.on("POST", "/requests") { FakeServer.error(429, "too_many_requests") }
            s.start()

            s.controller.requestHelp()
            runCurrent()

            assertEquals(UserError.TOO_MANY_REQUESTS, s.state.error)
        }

    @Test
    fun existingActiveRequestIsShownInsteadOfError() =
        runTest {
            val s = Setup(this)
            s.server.on("POST", "/requests") { FakeServer.error(409, "active_request_exists") }
            s.start()
            s.respondCurrent(helpRequest(RequestStatus.SEARCHING))

            s.controller.requestHelp()
            runCurrent()

            assertEquals(BlindScreen.Searching(REQUEST_ID), s.screen)
            assertNull(s.state.error)
        }

    @Test
    fun eventsMissedWithoutConnectionAreRecoveredAfterReconnect() =
        runTest {
            val s = Setup(this)
            s.respondCurrent(helpRequest(RequestStatus.SEARCHING))
            s.start()

            s.transport.last.drop()
            runCurrent()
            assertEquals(RealtimeStatus.RECONNECTING, s.state.connection)
            // Пока связи не было, волонтёр принял запрос.
            s.respond("GET", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ACCEPTED))
            advanceTimeBy(1_001)
            s.event(ServerEvent.Ready)

            assertEquals(BlindScreen.Call(REQUEST_ID, callCredentials), s.screen)
            assertEquals(RealtimeStatus.CONNECTED, s.state.connection)
        }

    @Test
    fun microphoneSwitchReachesTheCall() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)

            s.controller.setMicrophoneEnabled(false)

            assertEquals(false, s.call.microphone)
        }

    @Test
    fun blockedCameraIsRetriedWhenTheUserComesBack() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)

            // Всё работает — повторять нечего.
            s.controller.retryBlockedDevices()
            assertEquals(0, s.call.retries)

            // Звонок восстановлен после перезапуска, а разрешение на камеру за это время отозвали.
            s.call.report(CallState(CallConnection.CONNECTED, PeerPresence.PRESENT, camera = CameraState.BLOCKED))
            runCurrent()
            s.controller.retryBlockedDevices()

            assertEquals(1, s.call.retries)
        }

    @Test
    fun stopLeavesTheCall() =
        runTest {
            val s = Setup(this)
            s.startCall(volunteerJoined = true)

            s.controller.stop()
            runCurrent()

            assertTrue(s.call.disconnected)
            assertTrue(s.transport.last.closedByClient)
        }
}
