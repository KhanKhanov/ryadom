package ru.ryadom.shared.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.Device
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeResponse
import ru.ryadom.shared.testing.FakeServer
import ru.ryadom.shared.testing.MaxJitter
import ru.ryadom.shared.testing.NoJitter
import ru.ryadom.shared.testing.signedInStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushRegistrationTest {
    private class Setup(
        scope: TestScope,
    ) {
        val server = FakeServer(StandardTestDispatcher(scope.testScheduler))
        val api = ApiClient("http://server", server.engine, signedInStorage(), now = { scope.testScheduler.currentTime })
        val tokens = MutableStateFlow<String?>(null)
        val push = PushRegistration(api, PushProvider.FCM, tokens, scope.backgroundScope, random = NoJitter)

        init {
            server.on("POST", "/devices") { FakeServer.ok(Device.serializer(), Device(DEVICE_ID)) }
            server.on("DELETE", "/devices/$DEVICE_ID") { FakeResponse(204) }
            server.on("POST", "/auth/logout") { FakeResponse(204) }
        }

        val registrations get() = server.requestsTo("POST", "/devices").map { FakeServer.decode(RegisterDeviceRequest.serializer(), it) }
    }

    @Test
    fun registersTheTokenFromThePlatform() =
        runTest {
            val s = Setup(this)
            s.push.start()
            runCurrent()
            assertEquals(PushStatus.WAITING_FOR_TOKEN, s.push.status.value)

            s.tokens.value = "installation-1"
            runCurrent()

            assertEquals(listOf(RegisterDeviceRequest(PushProvider.FCM, "installation-1")), s.registrations)
            assertEquals(PushStatus.REGISTERED, s.push.status.value)
        }

    @Test
    fun newTokenIsRegisteredAgain() =
        runTest {
            val s = Setup(this)
            s.tokens.value = "installation-1"
            s.push.start()
            runCurrent()

            s.tokens.value = "installation-2"
            runCurrent()

            assertEquals(listOf("installation-1", "installation-2"), s.registrations.map { it.token })
        }

    @Test
    fun registrationIsRetriedWithoutConnection() =
        runTest {
            val s = Setup(this)
            s.server.on("POST", "/devices") { throw FakeNetworkFailure() }
            s.tokens.value = "installation-1"
            s.push.start()
            runCurrent()
            assertEquals(PushStatus.REGISTERING, s.push.status.value)

            s.server.on("POST", "/devices") { FakeServer.ok(Device.serializer(), Device(DEVICE_ID)) }
            advanceTimeBy(5_001)

            assertEquals(PushStatus.REGISTERED, s.push.status.value)
            assertEquals(2, s.registrations.size)
        }

    @Test
    fun rejectedTokenIsNotRetried() =
        runTest {
            val s = Setup(this)
            s.server.on("POST", "/devices") { FakeServer.error(400, "invalid_request") }
            s.tokens.value = "installation-1"
            s.push.start()
            runCurrent()
            advanceTimeBy(600_000)

            assertEquals(PushStatus.FAILED, s.push.status.value)
            assertEquals(1, s.registrations.size)
        }

    @Test
    fun deviceIsDeletedBeforeLogout() =
        runTest {
            val s = Setup(this)
            s.tokens.value = "installation-1"
            s.push.start()
            runCurrent()

            s.api.logout()

            val order = s.server.requests.map { "${it.method} ${it.path}" }
            assertTrue(order.indexOf("DELETE /devices/$DEVICE_ID") in 0 until order.indexOf("POST /auth/logout"), order.toString())
        }

    @Test
    fun stoppedRegistrationDoesNotTouchLogout() =
        runTest {
            val s = Setup(this)
            s.tokens.value = "installation-1"
            s.push.start()
            runCurrent()

            s.push.stop()
            s.api.logout()

            assertEquals(emptyList(), s.server.requestsTo("DELETE", "/devices/$DEVICE_ID"))
        }

    @Test
    fun retryPausesGrowAndAreSpread() {
        assertEquals(listOf(5_000L, 15_000L, 60_000L, 300_000L, 300_000L), (0..4).map { PushRegistration.retryPause(it, NoJitter) })
        assertEquals(3_750L, PushRegistration.retryPause(0, MaxJitter))
    }

    private companion object {
        const val DEVICE_ID = "0199a1b2-0000-7000-8000-0000000000d1"
    }
}
