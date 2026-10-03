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
import ru.ryadom.shared.api.Role
import ru.ryadom.shared.auth.AuthController
import ru.ryadom.shared.auth.AuthScreen
import ru.ryadom.shared.client.ApiClient
import ru.ryadom.shared.client.KtorRealtimeTransport
import ru.ryadom.shared.client.RealtimeConnection
import ru.ryadom.shared.help.BlindHelpController
import java.time.LocalTime
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Объекты приложения (вместо библиотеки внедрения зависимостей — их пока мало).
 * Живут, пока жив процесс: звонок не прерывается, когда Activity пересоздаётся (поворот экрана)
 * или уходит в фон. Всё вызывается из главного потока.
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

    private val blindSessionFlow = MutableStateFlow<BlindSession?>(null)

    /** Состояние незрячего — пока вошёл пользователь с ролью «незрячий». */
    val blindSession: StateFlow<BlindSession?> = blindSessionFlow.asStateFlow()

    /** Контроллер незрячего и всё, что живёт вместе с ним. */
    class BlindSession(
        val controller: BlindHelpController,
        val realtime: RealtimeConnection,
        val userId: String,
        internal val sideEffects: Job,
    )

    fun start() {
        auth.start()
        scope.launch {
            auth.state
                .map { state -> (state.screen as? AuthScreen.SignedIn)?.profile?.takeIf { it.role == Role.BLIND }?.id }
                .distinctUntilChanged()
                .collect(::switchBlindSession)
        }
        watchNetwork()
    }

    private fun switchBlindSession(userId: String?) {
        blindSessionFlow.value?.let { old ->
            old.sideEffects.cancel()
            old.controller.stop()
            CallForegroundService.update(appContext, null)
        }
        blindSessionFlow.value =
            userId?.let {
                val realtime = RealtimeConnection(api.realtimeUrl, api, KtorRealtimeTransport(api.http))
                val controller = BlindHelpController(api, realtime, calls, scope, localHour = { LocalTime.now().hour })
                controller.start()
                BlindSession(controller, realtime, userId, scope.launchBlindHelpSideEffects(appContext, controller))
            }
    }

    /**
     * Сеть появилась или сменилась — сразу переподключиться к серверу событий: не ждать паузы между
     * попытками и не держать соединение, оставшееся в прежней сети ([RealtimeConnection.reconnectNow]).
     */
    private fun watchNetwork() {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { blindSessionFlow.value?.realtime?.reconnectNow() }
                }
            },
        )
    }
}
