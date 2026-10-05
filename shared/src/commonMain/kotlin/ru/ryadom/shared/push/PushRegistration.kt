package ru.ryadom.shared.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.RegisterDeviceRequest
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.ApiClientException
import ru.ryadom.shared.client.shortenedRandomly
import kotlin.random.Random

/** Подключены ли push-уведомления о вызовах на этом устройстве. */
enum class PushStatus {
    /** Push-сервис платформы ещё не выдал адрес устройства (или на устройстве его нет). */
    WAITING_FOR_TOKEN,

    /** Сообщаем адрес серверу (или ждём связи, чтобы сообщить). */
    REGISTERING,

    /** Сервер знает устройство: вызовы придут, даже когда приложение закрыто. */
    REGISTERED,

    /** Сервер отказался регистрировать устройство — повтор не поможет. */
    FAILED,
}

/**
 * Push-уведомления о вызовах для волонтёра (docs/ARCHITECTURE.md, раздел 7): сообщает серверу адрес
 * устройства в push-сервисе (`POST /devices`) — при каждом запуске и каждый раз, когда push-сервис выдал
 * новый, — и удаляет устройство перед выходом (`DELETE /devices/{deviceId}`), иначе вызовы приходили бы
 * вышедшему волонтёру и занимали бы место в волне.
 *
 * Сам адрес выдаёт платформа: на Android — Firebase Installation ID из FCM ([tokens]).
 * Если нет связи, регистрация повторяется с растущими паузами, пока не получится.
 * Все методы — из главного потока, [scope] однопоточный.
 */
class PushRegistration(
    private val api: ApiClient,
    private val provider: PushProvider,
    private val tokens: StateFlow<String?>,
    scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val statusFlow = MutableStateFlow(PushStatus.WAITING_FOR_TOKEN)
    val status: StateFlow<PushStatus> = statusFlow.asStateFlow()

    /** id устройства на сервере — чтобы удалить его перед выходом. */
    private var deviceId: String? = null
    private var removeBeforeLogout: (() -> Unit)? = null

    fun start() {
        removeBeforeLogout = api.onBeforeLogout { deviceId?.let { api.deleteDevice(it) } }
        scope.launch {
            // Новый адрес — новая регистрация; незаконченную регистрацию прежнего адреса продолжать незачем.
            tokens.filterNotNull().distinctUntilChanged().collectLatest { register(it) }
        }
    }

    /** Пользователь вышел или сменил роль. Устройство на сервере остаётся: его удаляет задача перед выходом. */
    fun stop() {
        removeBeforeLogout?.invoke()
        removeBeforeLogout = null
        scope.cancel()
    }

    private suspend fun register(token: String) {
        statusFlow.value = PushStatus.REGISTERING
        var attempt = 0
        while (true) {
            try {
                deviceId = api.registerDevice(RegisterDeviceRequest(provider, token)).id
                statusFlow.value = PushStatus.REGISTERED
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiClientException.SessionEnded) {
                return
            } catch (e: ApiClientException.Network) {
                delay(retryPause(attempt++, random))
            } catch (e: Exception) {
                // Сервер отверг адрес (например, неверный формат) — повтор с тем же адресом не поможет.
                statusFlow.value = PushStatus.FAILED
                return
            }
        }
    }

    companion object {
        /** Паузы между попытками зарегистрировать устройство без связи. */
        val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 60_000L, 300_000L)

        /** Пауза перед повтором после [attempt] неудач: из [RETRY_DELAYS_MS], случайно короче — как у WebSocket. */
        fun retryPause(
            attempt: Int,
            random: Random,
        ): Long = RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)].shortenedRandomly(random)
    }
}
