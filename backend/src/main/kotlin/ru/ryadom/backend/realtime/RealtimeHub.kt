package ru.ryadom.backend.realtime

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import org.slf4j.LoggerFactory
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.ServerEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Открытые WebSocket-соединения пользователей и доставка им событий.
 *
 * Соединения живут в памяти процесса: сервер backend пока один (docs/ARCHITECTURE.md, раздел 10).
 * Если серверов станет несколько, события между ними нужно будет пересылать (например, через Redis).
 */
class RealtimeHub {
    // У одного пользователя может быть несколько соединений: телефон, вкладки браузера.
    private val sessions = ConcurrentHashMap<Uuid, Set<WebSocketSession>>()

    fun connect(
        userId: Uuid,
        session: WebSocketSession,
    ) {
        sessions.compute(userId) { _, current -> current.orEmpty() + session }
    }

    fun disconnect(
        userId: Uuid,
        session: WebSocketSession,
    ) {
        sessions.computeIfPresent(userId) { _, current -> (current - session).ifEmpty { null } }
    }

    /** Пользователи, у которых сейчас открыто хотя бы одно соединение. */
    fun connectedUsers(): Set<Uuid> = sessions.keys.toSet()

    /**
     * Отправляет [event] во все соединения пользователей [userIds]. Не ждёт медленных клиентов:
     * если очередь соединения переполнена, событие для него теряется, и клиент узнает актуальное
     * состояние запросом к API (так описано в контракте).
     */
    fun send(
        userIds: Collection<Uuid>,
        event: ServerEvent,
    ) {
        if (userIds.isEmpty()) return
        val text = Realtime.encode(event)
        for (userId in userIds.toSet()) {
            for (session in sessions[userId].orEmpty()) {
                val result = session.outgoing.trySend(Frame.Text(text))
                if (result.isFailure && !result.isClosed) {
                    log.warn("Realtime event dropped for user {}: client is too slow", userId)
                }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RealtimeHub::class.java)
    }
}
