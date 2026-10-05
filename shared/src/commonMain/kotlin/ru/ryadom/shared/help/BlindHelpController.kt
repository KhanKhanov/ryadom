package ru.ryadom.shared.help

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.call.CallFactory
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallSession
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.ApiClientException
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.client.toUserError

/**
 * Приложение незрячего: просит помощи, ждёт волонтёра, ведёт звонок, спрашивает оценку.
 * Экраны платформы только показывают [state] и вызывают методы-действия.
 *
 * Все методы вызываются из главного потока, а [scope] должен быть однопоточным (на Android —
 * `Dispatchers.Main`): так состояние меняется последовательно, без блокировок.
 *
 * @param localHour текущий час по местному времени устройства (для ночного текста «никто не ответил»).
 */
class BlindHelpController(
    private val api: ApiClient,
    private val realtime: RealtimeConnection,
    private val calls: CallFactory,
    scope: CoroutineScope,
    private val localHour: () -> Int,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val stateFlow = MutableStateFlow(BlindState())
    val state: StateFlow<BlindState> = stateFlow.asStateFlow()

    private var current: BlindState
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
            syncCall()
        }

    /** Идущий звонок на платформе; есть, пока экран — [BlindScreen.Call] (и звонок не завершается). */
    private var call: ActiveCall? = null

    private class ActiveCall(
        val requestId: String,
        val session: CallSession,
    )

    /** Подключается к серверу событий и восстанавливает незавершённый запрос. */
    fun start() {
        // UNDISPATCHED — подписаться сразу, до первого события: прошлые события сервер не повторяет.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { realtime.events.collect(::onEvent) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            realtime.status.collect { current = current.copy(connection = it) }
        }
        realtime.start(scope)
        scope.launch { resync() }
    }

    /** Пользователь вышел: отключиться от событий и звонка. После этого контроллер не используется. */
    fun stop() {
        scope.cancel()
        realtime.stop()
        call?.session?.disconnect()
        call = null
    }

    /** Большая кнопка «Позвать волонтёра». */
    fun requestHelp() {
        if (current.busy || current.screen !is BlindScreen.Ready) return
        runAction {
            try {
                apply(api.createRequest())
            } catch (e: ApiClientException.Server) {
                if (e.code != ApiErrorCodes.ACTIVE_REQUEST_EXISTS) throw e
                showActiveRequest()
            }
        }
    }

    fun cancelSearch() {
        val screen = current.screen as? BlindScreen.Searching ?: return
        if (current.busy) return
        runAction { apply(api.cancelRequest(screen.requestId), byUser = true) }
    }

    /**
     * Незрячий завершает звонок. Камера и микрофон выключаются сразу, а сервер узнаёт об этом,
     * как только будет связь: без подтверждения сервера запрос остался бы активным,
     * и попросить помощи снова было бы нельзя.
     */
    fun endCall() {
        val screen = current.screen as? BlindScreen.Call ?: return
        if (screen.ending) return
        current = current.copy(screen = screen.copy(ending = true), error = null)
        scope.launch { confirmCallEnded(screen.requestId) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        call?.session?.setMicrophoneEnabled(enabled)
    }

    /**
     * Приложение снова на экране — например, пользователь вернулся из настроек, где разрешил камеру
     * или микрофон. Если в звонке что-то из них было недоступно, пробуем включить ещё раз.
     */
    fun retryBlockedDevices() {
        val screen = current.screen as? BlindScreen.Call ?: return
        val blocked = screen.call.camera == CameraState.BLOCKED || screen.call.microphone == MicrophoneState.BLOCKED
        if (blocked) call?.session?.retryBlockedDevices()
    }

    /** Ответ на «Удалось получить помощь?». */
    fun rate(helped: Boolean) {
        val screen = current.screen as? BlindScreen.Rating ?: return
        if (current.busy) return
        runAction {
            try {
                api.rateRequest(screen.requestId, helped)
                current = current.copy(screen = BlindScreen.Ready(HelpOutcome.RATED))
            } catch (e: ApiClientException.Server) {
                // Оценить этот звонок нельзя — просто вернуться на главный экран.
                if (e.code != ApiErrorCodes.CALL_NOT_STARTED && e.code != ApiErrorCodes.NOT_FOUND) throw e
                current = current.copy(screen = BlindScreen.Ready())
            }
        }
    }

    fun skipRating() {
        if (current.screen !is BlindScreen.Rating || current.busy) return
        current = current.copy(screen = BlindScreen.Ready(), error = null)
    }

    private fun onEvent(event: ServerEvent) {
        when (event) {
            // Соединение готово (в том числе после обрыва): события без связи потеряны, перечитываем состояние.
            ServerEvent.Ready -> scope.launch { resync() }

            is ServerEvent.RequestEvent -> apply(event.request)
        }
    }

    /** Узнаёт у сервера актуальное состояние запроса: при запуске и после переподключения. */
    private suspend fun resync() {
        try {
            val trackedId =
                when (val screen = current.screen) {
                    is BlindScreen.Searching -> screen.requestId
                    is BlindScreen.Call -> screen.requestId
                    else -> null
                }
            val request = if (trackedId != null) api.getRequest(trackedId) else api.currentRequest()
            if (request != null) apply(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет связи — перечитаем при следующем подключении.
        } finally {
            if (current.screen == BlindScreen.Loading) current = current.copy(screen = BlindScreen.Ready())
        }
    }

    /**
     * Сервер не создал запрос: активный уже есть. Если он создан до перезапуска или на другом устройстве —
     * показываем его. Если это звонок, который незрячий уже завершил, а сервер тогда отказал
     * ([confirmCallEnded]), — в него не возвращаем: незрячий мог уйти от неподходящего волонтёра
     * (docs/ARCHITECTURE.md, раздел 9). Завершаем его ещё раз и просим помощи заново. Если запрос
     * успел закрыться сам — тоже просим заново. Так кнопка не молчит: либо новый поиск, либо ошибка.
     */
    private suspend fun showActiveRequest() {
        val request = api.currentRequest()
        when {
            request == null -> {
                apply(api.createRequest())
            }

            request.id == current.closedRequestId -> {
                api.cancelRequest(request.id)
                apply(api.createRequest())
            }

            else -> {
                apply(request)
            }
        }
    }

    private fun apply(
        request: HelpRequest,
        byUser: Boolean = false,
    ) {
        current = current.withRequest(request, isNightHour(localHour()), byUser)
    }

    private fun onCallState(
        requestId: String,
        call: CallState,
    ) {
        current = current.withCallState(requestId, call)
    }

    private suspend fun confirmCallEnded(requestId: String) {
        var attempt = 0
        while (true) {
            // Пока мы ждали, запрос мог закрыться сам (событие `request.ended`).
            val screen = current.screen as? BlindScreen.Call ?: return
            if (screen.requestId != requestId) return
            try {
                val request = api.cancelRequest(requestId)
                current = current.withRequest(request, isNightHour(localHour()), byUser = true).copy(error = null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiClientException.SessionEnded) {
                return
            } catch (e: ApiClientException.Network) {
                current = current.copy(error = e.toUserError())
                delay(END_CALL_RETRY_DELAYS_MS[minOf(attempt++, END_CALL_RETRY_DELAYS_MS.lastIndex)])
            } catch (e: Exception) {
                // Сервер отказал (например, запроса уже нет) — повтор не поможет. Звонок у нас всё равно закончен.
                current = current.copy(screen = BlindScreen.Ready(HelpOutcome.CALL_ENDED), closedRequestId = requestId, error = null)
                return
            }
        }
    }

    /** Действие пользователя: на время запроса к серверу кнопки неактивны, ошибка показывается. */
    private fun runAction(block: suspend () -> Unit) {
        current = current.copy(busy = true, error = null)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiClientException.SessionEnded) {
                // Приложение уже показывает вход.
            } catch (e: Exception) {
                current = current.copy(error = e.toUserError())
            } finally {
                current = current.copy(busy = false)
            }
        }
    }

    /** Звонок на платформе — ровно пока экран показывает звонок. */
    private fun syncCall() {
        val wanted = (current.screen as? BlindScreen.Call)?.takeUnless { it.ending }
        val active = call
        if (active != null && active.requestId != wanted?.requestId) {
            call = null
            active.session.disconnect()
        }
        if (wanted != null && call == null) {
            val requestId = wanted.requestId
            val session =
                calls.create(wanted.credentials, CallOptions(publishCamera = true)) { state ->
                    // Платформа сообщает из своего потока — переходим в поток контроллера.
                    scope.launch { onCallState(requestId, state) }
                }
            call = ActiveCall(requestId, session)
            session.connect()
        }
    }

    private companion object {
        /** Паузы между попытками сообщить серверу о конце звонка, если нет связи. */
        val END_CALL_RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 30_000L)
    }
}
