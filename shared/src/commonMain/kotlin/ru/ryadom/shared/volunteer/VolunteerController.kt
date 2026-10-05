package ru.ryadom.shared.volunteer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.ryadom.shared.api.ApiErrorCodes
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.api.UpdateProfileRequest
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.call.CallFactory
import ru.ryadom.shared.call.CallOptions
import ru.ryadom.shared.call.CallSession
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.ApiClientException
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.client.toUserError

/**
 * Приложение волонтёра: входящие вызовы, принятие, звонок (волонтёр — только со звуком, видит камеру
 * незрячего), оценка. Экраны платформы только показывают [state] и вызывают методы-действия.
 * Правила те же, что у кабинета на сайте (`web/src/volunteer/VolunteerScreen.tsx`).
 *
 * Как и у [ru.ryadom.shared.help.BlindHelpController], все методы вызываются из главного потока,
 * а [scope] — однопоточный.
 *
 * @param onProfileChanged сервер вернул новый профиль (волонтёр включил или выключил вызовы) —
 *   платформа передаёт его в [ru.ryadom.shared.auth.AuthController.profileChanged].
 */
class VolunteerController(
    private val api: ApiClient,
    private val realtime: RealtimeConnection,
    private val calls: CallFactory,
    scope: CoroutineScope,
    private val onProfileChanged: (UserProfile) -> Unit,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val stateFlow = MutableStateFlow(VolunteerState())
    val state: StateFlow<VolunteerState> = stateFlow.asStateFlow()

    private val waitingFlow = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)

    /**
     * После каждой сверки с сервером — id вызовов, которые ждут ответа этого волонтёра (без пропущенных
     * здесь). Уведомления о других вызовах платформа убирает: они закрылись, пока не было связи,
     * или пришли push-уведомлением уже после того, как их приняли.
     */
    val waitingSynced: SharedFlow<Set<String>> = waitingFlow.asSharedFlow()

    private var current: VolunteerState
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
            syncCall()
        }

    /** Звонок на платформе; есть, пока в состоянии есть звонок, который волонтёр не завершает. */
    private var call: ActiveCall? = null

    private class ActiveCall(
        val requestId: String,
        val session: CallSession,
    )

    private var removeBeforeLogout: (() -> Unit)? = null

    /** Подключается к серверу событий, восстанавливает идущий звонок и перечитывает вызовы. */
    fun start() {
        // UNDISPATCHED — подписаться сразу, до первого события: прошлые события сервер не повторяет.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { realtime.events.collect(::onEvent) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            realtime.status.collect { current = current.copy(connection = it) }
        }
        realtime.start(scope)
        // Выход во время звонка сначала завершает его: иначе звонок остался бы открытым на сервере,
        // незрячий ждал бы ушедшего волонтёра, а сам волонтёр не получал бы новых вызовов.
        removeBeforeLogout = api.onBeforeLogout { current.call?.let { api.cancelRequest(it.requestId) } }
        scope.launch { resync() }
    }

    /** Пользователь вышел или сменил роль: отключиться от событий и звонка. После этого контроллер не используется. */
    fun stop() {
        removeBeforeLogout?.invoke()
        removeBeforeLogout = null
        scope.cancel()
        realtime.stop()
        call?.session?.disconnect()
        call = null
    }

    /**
     * Перечитать вызовы и звонок: пришло push-уведомление (вызов мог прийти, пока не было связи с сервером
     * событий), волонтёр нажал на уведомление или вернулся в приложение.
     */
    fun refresh() {
        scope.launch { resync() }
    }

    /** «Принять». [requestId] может ещё не быть в списке — например, вызов принят из push-уведомления. */
    fun accept(requestId: String) {
        if (current.accepting != null || current.call != null) return
        current = current.acceptStarted(requestId)
        scope.launch {
            current =
                try {
                    current.acceptSucceeded(api.acceptRequest(requestId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    current.acceptFailed(requestId, AcceptFailure.RETRY)
                } catch (e: ApiClientException.Server) {
                    when (e.code) {
                        ApiErrorCodes.REQUEST_TAKEN -> {
                            current.acceptFailed(requestId, AcceptFailure.TAKEN)
                        }

                        ApiErrorCodes.REQUEST_CLOSED, ApiErrorCodes.NOT_FOUND -> {
                            current.acceptFailed(requestId, AcceptFailure.CLOSED)
                        }

                        ApiErrorCodes.ACTIVE_REQUEST_EXISTS -> {
                            // Звонок уже идёт — например, принят на другом устройстве. Покажем его.
                            scope.launch { resync() }
                            current.acceptFailed(requestId, AcceptFailure.ALREADY_IN_CALL)
                        }

                        else -> {
                            current.acceptFailed(requestId, AcceptFailure.RETRY, e.toUserError())
                        }
                    }
                } catch (e: Exception) {
                    current.acceptFailed(requestId, AcceptFailure.RETRY, e.toUserError())
                }
        }
    }

    /** «Пропустить»: вызов исчезает только на этом устройстве, сервер о нём не знает. */
    fun skip(requestId: String) {
        current = current.skip(requestId)
    }

    /**
     * Волонтёр завершает звонок. Звук выключается сразу, а сервер узнаёт об этом, как только будет связь:
     * пока запрос активен, волонтёр не получает новых вызовов, а незрячий ждёт его в звонке.
     */
    fun endCall() {
        val active = current.call ?: return
        if (active.ending) return
        current = current.endStarted(active.requestId)
        scope.launch { confirmCallEnded(active.requestId) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        call?.session?.setMicrophoneEnabled(enabled)
    }

    /** Приложение снова на экране (например, вернулись из настроек, где разрешили микрофон). */
    fun retryBlockedDevices() {
        if (current.call?.call?.microphone == MicrophoneState.BLOCKED) call?.session?.retryBlockedDevices()
    }

    /** Ответ на «Удалось помочь?». */
    fun rate(helped: Boolean) {
        val finished = current.finished ?: return
        if (current.busy) return
        current = current.copy(busy = true, error = null)
        scope.launch {
            current =
                try {
                    api.rateRequest(finished.requestId, helped)
                    current.ratingDone(rated = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    current.copy(busy = false)
                } catch (e: ApiClientException.Server) {
                    // Оценить этот звонок нельзя (звонка не было) — просто вернуться на главный экран.
                    if (e.code == ApiErrorCodes.CALL_NOT_STARTED || e.code == ApiErrorCodes.NOT_FOUND) {
                        current.ratingDone(rated = false)
                    } else {
                        current.copy(busy = false, error = e.toUserError())
                    }
                } catch (e: Exception) {
                    current.copy(busy = false, error = e.toUserError())
                }
        }
    }

    fun skipRating() {
        if (current.finished == null || current.busy) return
        current = current.ratingDone(rated = false)
    }

    /** Переключатель «Готов помогать» — поле профиля `notificationsEnabled`. */
    fun setReady(ready: Boolean) {
        if (current.busy) return
        current = current.copy(busy = true, error = null)
        scope.launch {
            current =
                try {
                    val profile = api.updateMe(UpdateProfileRequest(notificationsEnabled = ready))
                    onProfileChanged(profile)
                    val notice = if (profile.notificationsEnabled) VolunteerNotice.READY_ON else VolunteerNotice.READY_OFF
                    current.copy(busy = false).notify(notice)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiClientException.SessionEnded) {
                    current.copy(busy = false)
                } catch (e: Exception) {
                    current.copy(busy = false, error = e.toUserError())
                }
        }
    }

    private fun onEvent(event: ServerEvent) {
        when (event) {
            // Соединение готово (в том числе после обрыва): события без связи потеряны, перечитываем состояние.
            ServerEvent.Ready -> scope.launch { resync() }

            is ServerEvent.RequestEvent -> current = current.withEvent(event)
        }
    }

    /**
     * Узнаёт у сервера идущий звонок и вызовы, которые ждут ответа: при запуске, после переподключения,
     * по push-уведомлению. События, случившиеся без связи, сервер не повторяет.
     */
    private suspend fun resync() {
        try {
            val active = api.currentRequest()
            if (active != null && active.status.let { it == RequestStatus.ACCEPTED || it == RequestStatus.IN_CALL }) {
                current = current.withCallRestored(active)
            } else {
                current.call?.let { tracked -> current = current.withRequest(api.getRequest(tracked.requestId)) }
            }
            val incoming = api.incomingRequests()
            current = current.withIncomingSynced(incoming).copy(synced = true)
            waitingFlow.emit(incoming.map { it.id }.filter { it !in current.skipped }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет связи — перечитаем при следующем подключении.
        }
    }

    private suspend fun confirmCallEnded(requestId: String) {
        var attempt = 0
        while (true) {
            // Пока мы ждали, звонок мог закрыться сам (событие `request.ended`).
            if (current.call?.requestId != requestId) return
            try {
                api.cancelRequest(requestId)
                current = current.callEnded(requestId).copy(error = null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiClientException.SessionEnded) {
                return
            } catch (e: ApiClientException.Network) {
                current = current.copy(error = e.toUserError())
                delay(END_CALL_RETRY_DELAYS_MS[minOf(attempt++, END_CALL_RETRY_DELAYS_MS.lastIndex)])
            } catch (e: Exception) {
                // Сервер отказал (например, запрос уже закрыт) — повтор не поможет. Звонок у нас всё равно закончен.
                current = current.callEnded(requestId).copy(error = null)
                return
            }
        }
    }

    private fun onCallState(
        requestId: String,
        state: CallState,
    ) {
        current = current.withCallState(requestId, state)
    }

    /** Звонок на платформе — ровно пока в состоянии есть звонок, который волонтёр не завершает. */
    private fun syncCall() {
        val wanted = current.call?.takeUnless { it.ending }
        val active = call
        if (active != null && active.requestId != wanted?.requestId) {
            call = null
            active.session.disconnect()
        }
        if (wanted != null && call == null) {
            val requestId = wanted.requestId
            // Камеры у волонтёра нет: токен LiveKit разрешает ему только микрофон.
            val session =
                calls.create(wanted.credentials, CallOptions(publishCamera = false)) { state ->
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
