package ru.ryadom.android.volunteer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.ryadom.android.call.KeepScreenOn
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.push.PushStatus
import ru.ryadom.shared.volunteer.VolunteerController
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Как часто перепроверять, не началось ли время тишины. */
private const val QUIET_CHECK_INTERVAL_MS = 60_000L

/** Сколько ждать ответа сервера о вызовах, открытых из уведомления, прежде чем вернуть экран блокировки. */
private const val OPENED_FOR_CALL_SYNC_TIMEOUT_MS = 15_000L

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Разрешение на уведомления: на Android 13+ — отдельный запрос, раньше оно было у всех приложений. */
@SuppressLint("InlinedApi") // Константа из Android 13 подставляется при сборке, а проверяется только на 13+.
private fun Context.notificationsGranted() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)

/**
 * Экраны волонтёра, подключённые к [controller]: разрешения (микрофон перед «Принять», уведомления),
 * звонок (экран не гаснет, снимки экрана запрещены), показ поверх экрана блокировки, пока звонит вызов.
 *
 * @param push работает ли FCM на этом телефоне; [registration] — знает ли сервер устройство.
 * @param pendingAccept вызов, который приняли кнопкой в уведомлении: принять, как только экран открылся.
 * @param callIntents сколько раз приложение открывали из уведомления о вызове. Каждое открытие — показывать
 *   приложение поверх экрана блокировки, пока вызовы не сверены с сервером (дальше — пока вызов звонит).
 * @param peerVideo камера незрячего во время звонка.
 */
@Composable
fun VolunteerRoute(
    controller: VolunteerController,
    profile: UserProfile,
    push: PushAvailability,
    registration: PushStatus,
    roleBusy: Boolean,
    roleError: UserError?,
    pendingAccept: String?,
    onPendingAcceptHandled: () -> Unit,
    callIntents: Int,
    onNeedHelp: () -> Unit,
    onLogout: () -> Unit,
    peerVideo: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Разрешения и настройки перечитываются, когда пользователь возвращается в приложение (например, из настроек).
    var resumes by remember { mutableIntStateOf(0) }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }
    var microphoneRefused by rememberSaveable { mutableStateOf(false) }
    var acceptAfterPermission by rememberSaveable { mutableStateOf<String?>(null) }

    val microphoneLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resumes++
            val requestId = acceptAfterPermission
            acceptAfterPermission = null
            if (granted) {
                microphoneRefused = false
                requestId?.let(controller::accept)
            } else {
                microphoneDenied = true
                // Без микрофона незрячий волонтёра не услышит — принять вызов нельзя.
                if (requestId != null) microphoneRefused = true
            }
        }
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            resumes++
            if (!granted) notificationsDenied = true
        }

    val actions =
        remember(controller, microphoneLauncher, notificationsLauncher) {
            object : VolunteerActions {
                override fun accept(requestId: String) {
                    microphoneRefused = false
                    if (context.granted(Manifest.permission.RECORD_AUDIO)) {
                        controller.accept(requestId)
                    } else {
                        acceptAfterPermission = requestId
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                override fun skip(requestId: String) = controller.skip(requestId)

                override fun setReady(ready: Boolean) = controller.setReady(ready)

                override fun endCall() = controller.endCall()

                override fun setMicrophoneEnabled(enabled: Boolean) = controller.setMicrophoneEnabled(enabled)

                override fun rate(helped: Boolean) = controller.rate(helped)

                override fun skipRating() = controller.skipRating()

                override fun allowMicrophone() = microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)

                @SuppressLint("InlinedApi") // Кнопка есть только на Android 13+, где разрешение спрашивается.
                override fun allowNotifications() = notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                override fun allowFullScreen() {
                    val intent =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
                        } else {
                            appSettings(context)
                        }
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                override fun openSettings() = context.startActivity(appSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

                override fun needHelp() = onNeedHelp()

                override fun logout() = onLogout()
            }
        }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumes++
        // Вернулись из настроек, где разрешили микрофон, — включить его в звонке.
        controller.retryBlockedDevices()
        // Пока приложение было в фоне, связь с сервером могла пропасть — перечитать вызовы.
        controller.refresh()
    }

    // Вызов принят кнопкой в уведомлении.
    LaunchedEffect(pendingAccept) {
        pendingAccept?.let {
            actions.accept(it)
            onPendingAcceptHandled()
        }
    }

    // Время тишины начинается и кончается само — перепроверять раз в минуту.
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(QUIET_CHECK_INTERVAL_MS)
            now = ZonedDateTime.now()
        }
    }

    val readiness =
        remember(resumes, push, registration, microphoneDenied, notificationsDenied) {
            CallReadiness(
                microphoneGranted = context.granted(Manifest.permission.RECORD_AUDIO),
                notificationsGranted = context.notificationsGranted(),
                fullScreenAllowed = IncomingCallNotifications.canUseFullScreen(context),
                push = push,
                registration = registration,
                microphoneDeniedBefore = microphoneDenied,
                notificationsDeniedBefore = notificationsDenied,
            )
        }

    // Открыли из уведомления о вызове: до ответа сервера неизвестно, ждёт ли вызов, — экран блокировки не возвращаем.
    var awaitingSync by remember { mutableStateOf(false) }
    LaunchedEffect(callIntents) {
        if (callIntents == 0) return@LaunchedEffect
        awaitingSync = true
        coroutineScope {
            val synced = async(start = CoroutineStart.UNDISPATCHED) { controller.waitingSynced.first() }
            controller.refresh()
            withTimeoutOrNull(OPENED_FOR_CALL_SYNC_TIMEOUT_MS) { synced.await() }
            synced.cancel()
        }
        awaitingSync = false
    }

    val inCall = state.call != null
    if (inCall) {
        KeepScreenOn()
        SecureWindow()
    }
    ShowOverLockScreen(state.ringing || inCall || awaitingSync)

    VolunteerScreen(
        state = state,
        extras =
            VolunteerExtras(
                profile = profile,
                readiness = readiness,
                quietNow = isQuietNow(profile, now),
                microphoneRefused = microphoneRefused && !context.granted(Manifest.permission.RECORD_AUDIO),
                roleBusy = roleBusy,
                roleError = roleError,
            ),
        actions = actions,
        peerVideo = peerVideo,
        modifier = modifier,
    )
}

/** Сейчас время тишины по часовому поясу профиля. Неизвестный телефону пояс — считаем, что нет. */
internal fun isQuietNow(
    profile: UserProfile,
    now: ZonedDateTime,
): Boolean {
    val zone =
        try {
            ZoneId.of(profile.timezone)
        } catch (e: java.time.DateTimeException) {
            return false
        }
    return profile.doNotDisturb.contains(now.withZoneSameInstant(zone).format(TIME_FORMAT))
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

private fun appSettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
