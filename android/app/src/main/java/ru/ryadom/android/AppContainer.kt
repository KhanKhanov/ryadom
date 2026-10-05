package ru.ryadom.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.ryadom.android.call.CallForegroundService
import ru.ryadom.android.call.LiveKitCallFactory
import ru.ryadom.android.help.launchBlindHelpSideEffects
import ru.ryadom.android.push.FcmPush
import ru.ryadom.android.volunteer.IncomingCallNotifications
import ru.ryadom.android.volunteer.PushAvailability
import ru.ryadom.android.volunteer.appVisibility
import ru.ryadom.android.volunteer.launchVolunteerSideEffects
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushMessageType
import ru.ryadom.shared.api.PushProvider
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.auth.AuthController
import ru.ryadom.shared.auth.AuthScreen
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.KtorRealtimeTransport
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.help.BlindHelpController
import ru.ryadom.shared.push.PushRegistration
import ru.ryadom.shared.volunteer.VolunteerController
import java.time.LocalTime
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Объекты приложения (вместо библиотеки внедрения зависимостей — их пока мало).
 * Живут, пока жив процесс: звонок не прерывается, когда Activity пересоздаётся (поворот экрана)
 * или уходит в фон. Всё вызывается из главного потока, кроме [onPush] и [onPushToken] (их вызывает FCM).
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = MainScope()

    // OkHttp пингует WebSocket сам, настройка плагина Ktor на него не действует (RealtimeConnection.PING_INTERVAL_MS).
    private val engine = OkHttp.create { config { pingInterval(RealtimeConnection.PING_INTERVAL_MS, TimeUnit.MILLISECONDS) } }
    val api = ApiClient(BuildConfig.API_URL, engine, KeystoreSessionStorage(appContext))
    val auth = AuthController(api, scope, timeZoneId = { TimeZone.getDefault().id })
    val calls = LiveKitCallFactory(appContext, debugLogging = BuildConfig.DEBUG)
    val fcm = FcmPush(appContext)

    /** Адрес этого телефона в FCM (Firebase Installation ID); `null` — ещё не выдан. */
    private val pushTokens = MutableStateFlow<String?>(null)

    /** Приложение на экране. В фоне вызов приходит уведомлением; читается и из потока FCM. */
    @Volatile
    private var appVisible = false

    private val blindSessionFlow = MutableStateFlow<BlindSession?>(null)

    /** Состояние незрячего — пока вошёл пользователь с ролью «незрячий». */
    val blindSession: StateFlow<BlindSession?> = blindSessionFlow.asStateFlow()

    private val volunteerSessionFlow = MutableStateFlow<VolunteerSession?>(null)

    /** Состояние волонтёра — пока вошёл пользователь с ролью «волонтёр». */
    val volunteerSession: StateFlow<VolunteerSession?> = volunteerSessionFlow.asStateFlow()

    /** Вызов, принятый кнопкой в уведомлении, пока экран волонтёра ещё не открылся. */
    val pendingAccept = MutableStateFlow<String?>(null)

    /** Контроллер незрячего и всё, что живёт вместе с ним. */
    class BlindSession(
        val controller: BlindHelpController,
        val realtime: RealtimeConnection,
        val userId: String,
        internal val sideEffects: Job,
    )

    /** Контроллер волонтёра и всё, что живёт вместе с ним. [push] — `null`, если FCM на телефоне нет. */
    class VolunteerSession(
        val controller: VolunteerController,
        val realtime: RealtimeConnection,
        val push: PushRegistration?,
        val userId: String,
        internal val sideEffects: Job,
    )

    /** Кто вошёл: экраны и фоновые задачи зависят от пользователя и его роли. */
    private data class SignedInUser(
        val id: String,
        val role: Role?,
    )

    fun start() {
        auth.start()
        scope.launch {
            auth.state
                .map { state -> (state.screen as? AuthScreen.SignedIn)?.profile?.let { SignedInUser(it.id, it.role) } }
                .distinctUntilChanged()
                .collect(::switchSession)
        }
        scope.launch { appVisibility().collect { appVisible = it } }
        // Сеанс закончился (выход, истёк, блокировка) — отписать телефон от FCM: вызовы вышедшему волонтёру
        // приходить не должны. Устройство на сервере удаляет задача перед выходом (PushRegistration).
        scope.launch {
            api.sessionEnds.collect {
                fcm.unregister()
                pushTokens.value = null
                IncomingCallNotifications.cancelAllExcept(appContext)
            }
        }
        watchNetwork()
    }

    /** FCM выдал адрес телефона (FID) — его зарегистрирует [PushRegistration] вошедшего волонтёра. */
    fun onPushToken(token: String) {
        pushTokens.value = token
    }

    /**
     * Push от сервера (поток FCM): вызов пришёл или больше не ждёт ответа. Уведомление — сразу, ещё до
     * разговора с сервером: процесс мог быть запущен ради этого push, и экран волонтёра ещё не загружен.
     * Если приложение на экране, вызов звонит само — уведомление не нужно. Затем контроллер сверяет
     * вызовы с сервером: например, вызов пришёл, пока не было связи с сервером событий.
     */
    fun onPush(message: PushMessage) {
        // Push приходят только на телефоны волонтёров; без входа вызов всё равно не принять.
        if (!api.hasSession()) return
        when (message.type) {
            PushMessageType.REQUEST_INCOMING -> if (!appVisible) IncomingCallNotifications.show(appContext, message.requestId)

            // Приходит и на телефон, где вызов приняли: идущий звонок это не трогает — убирается только уведомление.
            PushMessageType.REQUEST_CLOSED -> IncomingCallNotifications.cancel(appContext, message.requestId)
        }
        scope.launch { volunteerSessionFlow.value?.controller?.refresh() }
    }

    /** «Пропустить» в уведомлении о вызове. */
    fun skipIncomingCall(requestId: String) {
        volunteerSessionFlow.value?.controller?.skip(requestId)
    }

    private fun switchSession(user: SignedInUser?) {
        blindSessionFlow.value?.let { old ->
            old.sideEffects.cancel()
            old.controller.stop()
            CallForegroundService.update(appContext, null)
        }
        volunteerSessionFlow.value?.let { old ->
            old.sideEffects.cancel()
            old.push?.stop()
            old.controller.stop()
        }
        blindSessionFlow.value = null
        volunteerSessionFlow.value = null
        pendingAccept.value = null
        when (user?.role) {
            Role.BLIND -> blindSessionFlow.value = startBlind(user.id)
            Role.VOLUNTEER -> volunteerSessionFlow.value = startVolunteer(user.id)
            Role.ADMIN, null -> Unit
        }
    }

    private fun startBlind(userId: String): BlindSession {
        val realtime = RealtimeConnection(api.realtimeUrl, api, KtorRealtimeTransport(api.http))
        val controller = BlindHelpController(api, realtime, calls, scope, localHour = { LocalTime.now().hour })
        controller.start()
        return BlindSession(controller, realtime, userId, scope.launchBlindHelpSideEffects(appContext, controller))
    }

    private fun startVolunteer(userId: String): VolunteerSession {
        val realtime = RealtimeConnection(api.realtimeUrl, api, KtorRealtimeTransport(api.http))
        val controller = VolunteerController(api, realtime, calls, scope, onProfileChanged = auth::profileChanged)
        controller.start()
        val push =
            if (fcm.availability == PushAvailability.AVAILABLE) {
                PushRegistration(api, PushProvider.FCM, pushTokens, scope).also {
                    it.start()
                    // При каждом запуске: FCM мог выдать новый FID, а сервер — потерять устройство.
                    fcm.register()
                }
            } else {
                null
            }
        return VolunteerSession(controller, realtime, push, userId, scope.launchVolunteerSideEffects(appContext, controller))
    }

    /**
     * Сеть появилась или сменилась — сразу переподключиться к серверу событий: не ждать паузы между
     * попытками и не держать соединение, оставшееся в прежней сети ([RealtimeConnection.reconnectNow]).
     * Если при запуске профиль не загрузился (экран «Нет связи»), повторить и это.
     */
    private fun watchNetwork() {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        auth.retry()
                        blindSessionFlow.value?.realtime?.reconnectNow()
                        volunteerSessionFlow.value?.realtime?.reconnectNow()
                    }
                }
            },
        )
    }
}
