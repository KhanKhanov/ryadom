package ru.ryadom.shared.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.ryadom.shared.api.ClientMessage
import ru.ryadom.shared.api.Realtime
import ru.ryadom.shared.api.ServerEvent
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Пауза перед повторной попыткой, случайно короче на долю до [RealtimeConnection.RECONNECT_JITTER]:
 * клиенты, потерявшие связь одновременно, не приходят снова в одну и ту же секунду.
 */
internal fun Long.shortenedRandomly(random: Random): Long =
    this - (this * RealtimeConnection.RECONNECT_JITTER * random.nextDouble()).roundToLong()

/** Что нужно соединению от клиента API: токены и завершение сеанса. Реализует [ApiClient]. */
interface RealtimeAuth {
    suspend fun accessToken(minValidityMs: Long): AccessToken

    suspend fun refreshNow(): AccessToken

    fun endSession(reason: SessionEndReason)
}

/** Открытое WebSocket-соединение: только текстовые сообщения. */
interface RealtimeSocket {
    /** Сообщения сервера. Заканчивается, когда соединение закрыто. */
    val incoming: Flow<String>

    suspend fun send(text: String)

    /** Код, с которым сервер закрыл соединение (после окончания [incoming]); `null` — связь оборвалась. */
    suspend fun closeCode(): Short?

    suspend fun close()
}

/** Открывает WebSocket. Настоящий — [KtorRealtimeTransport], в тестах — поддельный. */
fun interface RealtimeTransport {
    suspend fun connect(url: String): RealtimeSocket
}

/** WebSocket через Ktor (на Android — движок OkHttp). */
class KtorRealtimeTransport(
    private val http: HttpClient,
) : RealtimeTransport {
    override suspend fun connect(url: String): RealtimeSocket {
        val session = http.webSocketSession(url)
        return object : RealtimeSocket {
            override val incoming: Flow<String> =
                session.incoming
                    .consumeAsFlow()
                    .filterIsInstance<Frame.Text>()
                    .map { it.readText() }

            override suspend fun send(text: String) = session.send(Frame.Text(text))

            override suspend fun closeCode(): Short? = session.closeReason.await()?.code

            override suspend fun close() = session.close(CloseReason(CloseReason.Codes.NORMAL, ""))
        }
    }
}

/**
 * - [CONNECTING] — первое подключение;
 * - [CONNECTED] — вход выполнен, события приходят;
 * - [RECONNECTING] — связь пропала, ждём следующей попытки.
 */
enum class RealtimeStatus { CONNECTING, CONNECTED, RECONNECTING }

/**
 * Соединение с сервером событий (WebSocket `/ws`, docs/api/openapi.yaml). Само входит
 * (первым сообщением `auth`), переподключается после обрывов и отправляет новый токен до истечения старого.
 * Неизвестные сообщения пропускает: их может прислать новая версия сервера.
 *
 * События, случившиеся без связи, сервер не повторяет. Поэтому после каждого [ServerEvent.Ready]
 * (в том числе после переподключения) клиент перечитывает состояние через REST API.
 *
 * @param random источник случайных пауз ([reconnectPause]); в тестах — предсказуемый.
 */
class RealtimeConnection(
    private val url: String,
    private val auth: RealtimeAuth,
    private val transport: RealtimeTransport,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random.Default,
) {
    private val eventFlow = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)

    /** [ServerEvent.Ready] и события о запросах. Подписывайтесь до [start]: прошлые события не повторяются. */
    val events: SharedFlow<ServerEvent> = eventFlow.asSharedFlow()

    private val statusFlow = MutableStateFlow(RealtimeStatus.CONNECTING)
    val status: StateFlow<RealtimeStatus> = statusFlow.asStateFlow()

    private var job: Job? = null
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Сколько попыток подряд не дошли до `ready`. */
    private var failures = 0

    /** Сервер отверг токен (4401): при следующем подключении сначала обновить его. */
    private var forceRefresh = false

    /** Чтение открытого соединения: [reconnectNow] обрывает его, не дожидаясь, пока соединение закроется само. */
    private var reading: Job? = null

    /** Соединение оборвал [reconnectNow] — это не сбой, новое открывается сразу. */
    private var dropRequested = false

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Сеть появилась или сменилась (Wi-Fi → мобильная): подключиться сразу, не дожидаясь паузы.
     * Открытое соединение могло остаться в прежней сети и умереть без единого сигнала — пинг ([PING_INTERVAL_MS])
     * заметил бы это не сразу, а события (волонтёр найден) терялись бы. Поэтому его обрываем и открываем новое;
     * пропущенное клиент перечитает после `ready`. Статус при этом не меняется: это не потеря связи.
     */
    fun reconnectNow() {
        wakeUp.trySend(Unit)
        val current = reading ?: return
        dropRequested = true
        current.cancel()
    }

    private suspend fun run() {
        while (true) {
            if (failures > 0) statusFlow.value = RealtimeStatus.RECONNECTING
            val closeCode =
                try {
                    session()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    // Сеанс закончился — переподключаться незачем, приложение уже показывает вход.
                    return
                } catch (e: Exception) {
                    null // Не подключиться или связь оборвалась.
                }
            if (dropRequested) {
                dropRequested = false
                wakeUp.tryReceive()
                continue
            }
            if (closeCode == Realtime.CLOSE_BANNED) {
                auth.endSession(SessionEndReason.BANNED)
                return
            }
            // Токен отвергнут (истёк, пока телефон спал): обновить и сразу подключиться.
            // Если это повторяется до `ready`, дело не в токене — переподключаемся с паузами, как при обрыве.
            val unauthorized = closeCode == Realtime.CLOSE_UNAUTHORIZED
            if (unauthorized) forceRefresh = true
            val pause = if (unauthorized && failures == 0) 0L else reconnectPause(failures, random)
            failures++
            statusFlow.value = RealtimeStatus.RECONNECTING
            withTimeoutOrNull(pause) { wakeUp.receive() }
        }
    }

    /** Одно соединение: от открытия до закрытия. Возвращает код закрытия (`null` — оборвалось или его оборвали). */
    private suspend fun session(): Short? {
        val socket = transport.connect(url)
        try {
            val dropped =
                coroutineScope {
                    val authJob = launch { authenticate(socket) }
                    val reader = launch { socket.incoming.collect { text -> onMessage(text) } }
                    reading = reader
                    reader.join()
                    authJob.cancel()
                    reader.isCancelled
                }
            return if (dropped) null else socket.closeCode()
        } finally {
            reading = null
            withContext(NonCancellable) {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Уже закрыто.
                }
            }
        }
    }

    /** Отправляет access-токен: первым сообщением после открытия и потом перед каждым истечением токена. */
    private suspend fun authenticate(socket: RealtimeSocket) {
        while (true) {
            val token =
                try {
                    if (forceRefresh) auth.refreshNow() else auth.accessToken(REAUTH_BEFORE_EXPIRY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    throw e
                } catch (e: Exception) {
                    // Нет сети для обновления токена. Пока старый токен действует, соединение живёт; попробуем позже.
                    delay(REAUTH_RETRY_MS)
                    continue
                }
            forceRefresh = false
            socket.send(Realtime.encode(ClientMessage.Auth(token.value)))
            delay((token.expiresAt - REAUTH_BEFORE_EXPIRY_MS - now()).coerceAtLeast(REAUTH_MIN_DELAY_MS))
        }
    }

    private suspend fun onMessage(text: String) {
        // Неизвестный тип или повреждённое сообщение — пропускаем; состояние перечитаем после переподключения.
        val event = Realtime.decodeServerEvent(text) ?: return
        if (event is ServerEvent.Ready) {
            failures = 0
            statusFlow.value = RealtimeStatus.CONNECTED
        }
        eventFlow.emit(event)
    }

    companion object {
        /** Паузы перед повторными попытками подключиться: чем дольше нет связи, тем реже попытки. */
        val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /**
         * На какую долю пауза может быть короче, чем в [RECONNECT_DELAYS_MS]. Когда сервер перезапускается
         * (обновление), связь теряют все клиенты разом; одинаковые паузы привели бы их обратно в одну и ту же
         * секунду. Пауза только сокращается: дольше, чем обещано, клиент без связи не ждёт.
         */
        const val RECONNECT_JITTER = 0.25

        /** Пауза перед попыткой после [failures] неудач подряд: из [RECONNECT_DELAYS_MS], случайно короче на долю до [RECONNECT_JITTER]. */
        fun reconnectPause(
            failures: Int,
            random: Random,
        ): Long = RECONNECT_DELAYS_MS[failures.coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)].shortenedRandomly(random)

        /**
         * Как часто клиент проверяет соединение (WebSocket ping). Без проверки оборванное соединение
         * (телефон сменил сеть, вышел из зоны покрытия) выглядит живым, и события — волонтёр найден,
         * никто не ответил — теряются. Нет ответа до следующего пинга — соединение считается оборванным
         * и открывается заново. Задаётся в плагине WebSockets ([ApiClient.http]); движок OkHttp пингует
         * сам и настраивается тем же значением на платформе (`AppContainer`).
         */
        const val PING_INTERVAL_MS = 20_000L

        /** За сколько до истечения access-токена отправить в соединение новый (иначе сервер закроет его). */
        const val REAUTH_BEFORE_EXPIRY_MS = 60_000L

        /** Если обновить токен не удалось (нет сети), следующая попытка — через это время. */
        const val REAUTH_RETRY_MS = 10_000L

        /** Не чаще раза в секунду, даже если часы устройства врут. */
        private const val REAUTH_MIN_DELAY_MS = 1_000L
    }
}
