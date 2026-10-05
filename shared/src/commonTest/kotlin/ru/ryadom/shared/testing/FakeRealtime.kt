package ru.ryadom.shared.testing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.client.RealtimeSocket
import ru.ryadom.shared.client.RealtimeTransport
import kotlin.random.Random

/** Поддельное WebSocket-соединение: тест присылает сообщения «от сервера» и смотрит, что отправил клиент. */
class FakeSocket : RealtimeSocket {
    val sent = mutableListOf<String>()
    private val messages = Channel<String>(Channel.UNLIMITED)
    private var code: Short? = null
    var closedByClient = false
        private set

    override val incoming: Flow<String> = messages.consumeAsFlow()

    override suspend fun send(text: String) {
        sent += text
    }

    override suspend fun closeCode(): Short? = code

    override suspend fun close() {
        closedByClient = true
        messages.close()
    }

    fun receive(text: String) {
        messages.trySend(text)
    }

    fun receive(event: ServerEvent) = receive(Realtime.encode(event))

    /** Сервер закрыл соединение с кодом [code]. */
    fun serverClose(code: Short) {
        this.code = code
        messages.close()
    }

    /** Связь оборвалась без кода закрытия. */
    fun drop() {
        messages.close(FakeNetworkFailure())
    }
}

/** Поддельный транспорт: каждое подключение — новый [FakeSocket]; [failConnect] — сервер недоступен. */
class FakeTransport : RealtimeTransport {
    val sockets = mutableListOf<FakeSocket>()
    var failConnect = false

    val last: FakeSocket get() = sockets.last()

    override suspend fun connect(url: String): RealtimeSocket {
        if (failConnect) throw FakeNetworkFailure()
        return FakeSocket().also { sockets += it }
    }
}

/** «Случайные» числа, всегда наименьшие: пауза переподключения ровно как в `RECONNECT_DELAYS_MS`, без разброса. */
object NoJitter : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

/** «Случайные» числа, всегда наибольшие: пауза переподключения сокращена на весь допустимый разброс. */
object MaxJitter : Random() {
    override fun nextBits(bitCount: Int): Int = -1 ushr (32 - bitCount)
}
