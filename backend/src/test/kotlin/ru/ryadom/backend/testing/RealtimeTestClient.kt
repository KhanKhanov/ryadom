package ru.ryadom.backend.testing

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.ServerEvent
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** WebSocket `/ws` в тесте: отправка сообщений и ожидание событий с таймаутом, чтобы тест не зависал. */
class RealtimeTestClient(
    private val session: DefaultClientWebSocketSession,
) {
    suspend fun send(message: ClientMessage) = sendText(Realtime.encode(message))

    suspend fun sendText(text: String) = session.send(Frame.Text(text))

    /** Следующее событие. Падает, если его нет за 5 секунд. */
    suspend fun next(): ServerEvent {
        val frame =
            withTimeoutOrNull(5.seconds) { session.incoming.receiveCatching().getOrNull() }
                ?: fail("No realtime event within 5 seconds")
        val text = (frame as? Frame.Text)?.readText() ?: fail("Unexpected frame $frame")
        return Realtime.decodeServerEvent(text) ?: fail("Unknown event: $text")
    }

    /** Следующее событие нужного типа. */
    suspend inline fun <reified T : ServerEvent> nextOf(): T = next() as? T ?: fail("Expected ${T::class.simpleName}")

    /** Проверяет, что за короткое время событий не пришло. */
    suspend fun assertNoEvents() {
        val frame = withTimeoutOrNull(300.milliseconds) { session.incoming.receiveCatching().getOrNull() }
        assertNull(frame, "Unexpected realtime event")
    }

    /** Ждёт, пока сервер закроет соединение, и возвращает причину. */
    suspend fun awaitClose(): CloseReason? = withTimeout(5.seconds) { session.closeReason.await() }

    suspend fun close() = session.close()
}
