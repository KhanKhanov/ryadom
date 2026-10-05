package ru.ryadom.shared.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.testing.FakeNetworkFailure
import ru.ryadom.shared.testing.FakeTransport
import ru.ryadom.shared.testing.MaxJitter
import ru.ryadom.shared.testing.NoJitter
import ru.ryadom.shared.testing.helpRequest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealtimeConnectionTest {
    /** Поддельные токены: каждый новый действует 15 минут от текущего виртуального времени. */
    private class FakeAuth(
        private val scope: TestScope,
    ) : RealtimeAuth {
        var issued = 0
        var refreshes = 0
        var failWith: Exception? = null
        val ended = mutableListOf<SessionEndReason>()
        private var token: AccessToken? = null

        override suspend fun accessToken(minValidityMs: Long): AccessToken {
            failWith?.let { throw it }
            val current = token
            if (current != null && current.expiresAt - scope.testScheduler.currentTime > minValidityMs) return current
            return issue()
        }

        override suspend fun refreshNow(): AccessToken {
            failWith?.let { throw it }
            refreshes++
            return issue()
        }

        override fun endSession(reason: SessionEndReason) {
            ended += reason
        }

        private fun issue() = AccessToken("token-${++issued}", scope.testScheduler.currentTime + 900_000).also { token = it }
    }

    private class Setup(
        scope: TestScope,
    ) {
        val transport = FakeTransport()
        val auth = FakeAuth(scope)
        val connection = RealtimeConnection("ws://server/ws", auth, transport, now = { scope.testScheduler.currentTime }, random = NoJitter)
        val events = mutableListOf<ServerEvent>()

        init {
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { connection.events.collect { events += it } }
            connection.start(scope.backgroundScope)
        }
    }

    private fun authMessage(token: String) = Realtime.encode(ClientMessage.Auth(token))

    @Test
    fun sendsTokenFirstAndReportsReadyAndEvents() =
        runTest {
            val s = Setup(this)
            runCurrent()

            assertEquals(listOf(authMessage("token-1")), s.transport.last.sent)
            assertEquals(RealtimeStatus.CONNECTING, s.connection.status.value)

            s.transport.last.receive(ServerEvent.Ready)
            s.transport.last.receive(ServerEvent.RequestAccepted(helpRequest(RequestStatus.ACCEPTED)))
            runCurrent()

            assertEquals(RealtimeStatus.CONNECTED, s.connection.status.value)
            assertEquals(ServerEvent.Ready, s.events[0])
            assertEquals(RequestStatus.ACCEPTED, (s.events[1] as ServerEvent.RequestAccepted).request.status)
        }

    @Test
    fun unknownMessagesAreSkipped() =
        runTest {
            val s = Setup(this)
            runCurrent()

            s.transport.last.receive("""{"type":"request.brand_new","request":{}}""")
            s.transport.last.receive("not json")
            s.transport.last.receive(ServerEvent.Ready)
            runCurrent()

            assertEquals(listOf<ServerEvent>(ServerEvent.Ready), s.events)
        }

    @Test
    fun reconnectsWithGrowingPausesAfterConnectionLoss() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.transport.last.receive(ServerEvent.Ready)
            runCurrent()

            s.transport.last.drop()
            runCurrent()
            assertEquals(RealtimeStatus.RECONNECTING, s.connection.status.value)
            assertEquals(1, s.transport.sockets.size)

            advanceTimeBy(1_001)
            assertEquals(2, s.transport.sockets.size)

            // Без `ready` вторая неудача подряд — пауза длиннее.
            s.transport.last.drop()
            runCurrent()
            advanceTimeBy(1_001)
            assertEquals(2, s.transport.sockets.size)
            advanceTimeBy(1_000)
            assertEquals(3, s.transport.sockets.size)
        }

    @Test
    fun unavailableServerIsRetried() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.transport.failConnect = true
            s.transport.last.drop()
            runCurrent()

            advanceTimeBy(60_000)
            assertEquals(1, s.transport.sockets.size)
            s.transport.failConnect = false
            advanceTimeBy(30_001)

            assertEquals(2, s.transport.sockets.size)
        }

    @Test
    fun rejectedTokenIsRefreshedAndConnectionReopensImmediately() =
        runTest {
            val s = Setup(this)
            runCurrent()

            s.transport.last.serverClose(Realtime.CLOSE_UNAUTHORIZED)
            runCurrent()

            assertEquals(1, s.auth.refreshes)
            assertEquals(2, s.transport.sockets.size)
            assertEquals(listOf(authMessage("token-2")), s.transport.last.sent)
        }

    @Test
    fun bannedUserSessionEndsAndConnectionStops() =
        runTest {
            val s = Setup(this)
            runCurrent()

            s.transport.last.serverClose(Realtime.CLOSE_BANNED)
            runCurrent()
            advanceTimeBy(120_000)

            assertEquals(listOf(SessionEndReason.BANNED), s.auth.ended)
            assertEquals(1, s.transport.sockets.size)
        }

    @Test
    fun newTokenIsSentBeforeTheOldOneExpires() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.transport.last.receive(ServerEvent.Ready)

            // Токен живёт 15 минут, новый уходит за минуту до истечения.
            advanceTimeBy(14 * 60_000 - 1_000)
            assertEquals(1, s.transport.last.sent.size)
            advanceTimeBy(2_000)

            assertEquals(listOf(authMessage("token-1"), authMessage("token-2")), s.transport.last.sent)
        }

    @Test
    fun endedSessionStopsReconnecting() =
        runTest {
            val s = Setup(this)
            s.auth.failWith = ApiClientException.SessionEnded(SessionEndReason.EXPIRED)
            runCurrent()
            advanceTimeBy(120_000)

            assertEquals(1, s.transport.sockets.size)
            assertTrue(s.transport.last.closedByClient)
        }

    @Test
    fun tokenRefreshWithoutNetworkIsRetriedLater() =
        runTest {
            val s = Setup(this)
            s.auth.failWith = ApiClientException.Network(FakeNetworkFailure())
            runCurrent()
            assertTrue(
                s.transport.last.sent
                    .isEmpty(),
            )

            s.auth.failWith = null
            advanceTimeBy(RealtimeConnection.REAUTH_RETRY_MS + 1)

            assertEquals(listOf(authMessage("token-1")), s.transport.last.sent)
        }

    @Test
    fun reconnectNowSkipsThePause() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.transport.last.drop()
            runCurrent()
            advanceTimeBy(1_001)
            // Вторая неудача без `ready` — пауза 2 секунды, но сеть появилась раньше.
            s.transport.last.drop()
            runCurrent()

            s.connection.reconnectNow()
            runCurrent()

            assertEquals(3, s.transport.sockets.size)
        }

    @Test
    fun reconnectNowReplacesAnOpenConnectionThatMayBeDead() =
        runTest {
            val s = Setup(this)
            runCurrent()
            s.transport.last.receive(ServerEvent.Ready)
            runCurrent()
            val old = s.transport.last

            // Телефон перешёл с Wi-Fi на мобильную сеть: старое соединение молчит, но само не закроется.
            s.connection.reconnectNow()
            runCurrent()

            assertTrue(old.closedByClient)
            assertEquals(2, s.transport.sockets.size)
            assertEquals(listOf(authMessage("token-1")), s.transport.last.sent)
            // Это не потеря связи: «Нет связи с сервером» не объявляется.
            assertEquals(RealtimeStatus.CONNECTED, s.connection.status.value)

            s.transport.last.receive(ServerEvent.Ready)
            runCurrent()
            assertEquals(listOf<ServerEvent>(ServerEvent.Ready, ServerEvent.Ready), s.events, "после ready клиент перечитает пропущенное")

            // Следующий обрыв — обычный: пауза перед переподключением не пропадает.
            s.transport.last.drop()
            runCurrent()
            assertEquals(RealtimeStatus.RECONNECTING, s.connection.status.value)
            assertEquals(2, s.transport.sockets.size)
        }

    @Test
    fun reconnectPausesAreSpreadButNeverLonger() {
        assertEquals(
            listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L),
            (0..5).map { RealtimeConnection.reconnectPause(it, NoJitter) },
        )
        assertEquals(listOf(750L, 1_500L, 3_750L, 7_500L, 22_500L), (0..4).map { RealtimeConnection.reconnectPause(it, MaxJitter) })

        val pauses = (1..200).map { RealtimeConnection.reconnectPause(0, Random(it)) }
        assertTrue(pauses.all { it in 750L..1_000L })
        assertTrue(pauses.distinct().size > 50, "паузы должны различаться, чтобы клиенты не подключались разом")
    }

    @Test
    fun clientsLosingConnectionTogetherDoNotReconnectTogether() =
        runTest {
            // Клиент с наибольшим разбросом приходит раньше обещанной секунды.
            val transport = FakeTransport()
            val connection =
                RealtimeConnection("ws://server/ws", FakeAuth(this), transport, now = { testScheduler.currentTime }, random = MaxJitter)
            connection.start(backgroundScope)
            runCurrent()
            transport.last.drop()
            runCurrent()

            advanceTimeBy(749)
            assertEquals(1, transport.sockets.size)
            advanceTimeBy(2)
            assertEquals(2, transport.sockets.size)
        }

    @Test
    fun stopClosesTheConnection() =
        runTest {
            val s = Setup(this)
            runCurrent()

            s.connection.stop()
            runCurrent()
            advanceTimeBy(60_000)

            assertTrue(s.transport.last.closedByClient)
            assertEquals(1, s.transport.sockets.size)
        }
}
