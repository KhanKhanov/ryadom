package ru.ryadom.shared.help

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.call.CallConnection
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CallStatus
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.call.status
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.testing.FakeCalls
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.FakeTransport
import ru.ryadom.shared.testing.OTHER_REQUEST_ID
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
    private class Setup(
        private val scope: TestScope,
        hour: Int = 12,
    ) {
        val server = FakeServer(StandardTestDispatcher(scope.testScheduler))
        val api = ApiClient("http://server", server.engine, signedInStorage(), now = { scope.testScheduler.currentTime })
        val transport = FakeTransport()
        val realtime = RealtimeConnection(api.realtimeUrl, api, transport, now = { scope.testScheduler.currentTime })
        val fakeCalls = FakeCalls()
        val calls get() = fakeCalls.created
        val controller = BlindHelpController(api, realtime, fakeCalls, scope.backgroundScope, localHour = { hour })

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

        /** Звонок закончен у нас, а на сервере остался открытым: сервер отказал в завершении. */
        fun endCallRefusedByServer() {
            startCall(volunteerJoined = false)
            server.on("DELETE", "/requests/$REQUEST_ID") { FakeServer.error(500, "internal_error") }
            controller.endCall()
            scope.runCurrent()
            assertEquals(BlindScreen.Ready(HelpOutcome.CALL_ENDED), screen)
            server.on("POST", "/requests") {
                // Пока прежний запрос не закрыт, новый сервер не создаёт.
                if (server.requestsTo("DELETE", "/requests/$REQUEST_ID").size < 2) {
                    FakeServer.error(409, "active_request_exists")
                } else {
                    FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.SEARCHING, id = OTHER_REQUEST_ID))
                }
            }
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
    fun callLeftOpenOnServerIsClosedAgainBeforeAskingForHelp() =
        runTest {
            val s = Setup(this)
            s.endCallRefusedByServer()
            s.respond("DELETE", "/requests/$REQUEST_ID", helpRequest(RequestStatus.ENDED))

            s.controller.requestHelp()
            runCurrent()

            // Незрячий сам завершил тот звонок — в него не возвращаем, а ищем другого волонтёра.
            assertEquals(BlindScreen.Searching(OTHER_REQUEST_ID), s.screen)
            assertEquals(1, s.calls.size)
            assertEquals(2, s.server.requestsTo("DELETE", "/requests/$REQUEST_ID").size)
            assertNull(s.state.error)
        }

    @Test
    fun callLeftOpenOnServerThatCannotBeClosedIsReported() =
        runTest {
            val s = Setup(this)
            s.endCallRefusedByServer()

            s.controller.requestHelp()
            runCurrent()

            // Сервер снова отказал — кнопка не молчит, а говорит об ошибке.
            assertEquals(BlindScreen.Ready(HelpOutcome.CALL_ENDED), s.screen)
            assertEquals(UserError.UNKNOWN, s.state.error)
            assertEquals(1, s.calls.size)
            assertFalse(s.state.busy)
        }

    @Test
    fun activeRequestClosedMeanwhileIsCreatedAgain() =
        runTest {
            val s = Setup(this)
            var attempts = 0
            s.server.on("POST", "/requests") {
                if (attempts++ == 0) {
                    FakeServer.error(409, "active_request_exists")
                } else {
                    FakeServer.ok(HelpRequest.serializer(), helpRequest(RequestStatus.SEARCHING))
                }
            }
            s.start()

            // Пока ответ 409 шёл к нам, прежний запрос закрылся: текущего запроса уже нет.
            s.controller.requestHelp()
            runCurrent()

            assertEquals(BlindScreen.Searching(REQUEST_ID), s.screen)
            assertEquals(2, s.server.requestsTo("POST", "/requests").size)
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
