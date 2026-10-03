package ru.ryadom.android.help

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
import androidx.compose.runtime.getValue
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
import ru.ryadom.android.call.KeepScreenOn
import ru.ryadom.shared.help.BlindHelpController
import ru.ryadom.shared.help.BlindScreen

/** Без камеры волонтёр не увидит, что нужно, без микрофона — не услышит. */
private val CALL_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/**
 * Что спросить перед первым запросом помощи: камеру и микрофон, а на Android 13+ ещё и уведомления —
 * без них уведомление «Ищем волонтёра» / «Идёт звонок» не видно в шторке, и через него не вернуться
 * в приложение. Отказ в уведомлениях звонку не мешает: поиск и звонок работают и без них.
 *
 * Константа POST_NOTIFICATIONS из Android 13 подставляется в код при сборке, а запрашивается
 * только на Android 13+ — поэтому предупреждение InlinedApi здесь ложное.
 */
@SuppressLint("InlinedApi")
internal fun permissionsToRequest(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) CALL_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS else CALL_PERMISSIONS

private fun Context.hasCallPermissions() =
    CALL_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * Экраны незрячего, подключённые к [controller]. Перед первым запросом помощи спрашивает
 * разрешения на камеру и микрофон; во время звонка не даёт экрану погаснуть.
 */
@Composable
fun BlindHelpRoute(
    controller: BlindHelpController,
    onLogout: () -> Unit,
    videoPreview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsDenied = !context.hasCallPermissions()
            if (!permissionsDenied) controller.requestHelp()
        }
    val actions =
        remember(controller, permissionLauncher) {
            object : BlindHelpActions {
                override fun requestHelp() {
                    if (context.hasCallPermissions()) controller.requestHelp() else permissionLauncher.launch(permissionsToRequest())
                }

                override fun cancelSearch() = controller.cancelSearch()

                override fun endCall() = controller.endCall()

                override fun setMicrophoneEnabled(enabled: Boolean) = controller.setMicrophoneEnabled(enabled)

                override fun rate(helped: Boolean) = controller.rate(helped)

                override fun skipRating() = controller.skipRating()

                override fun openSettings() {
                    // Если пользователь отказал дважды, Android больше не показывает запрос — только настройки.
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                override fun logout() = onLogout()
            }
        }
    // Вернулись в приложение (например, из настроек, где разрешили камеру) — включить то, что было недоступно.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.retryBlockedDevices() }
    if (state.screen is BlindScreen.Call) KeepScreenOn()
    BlindHelpScreen(
        state = state,
        actions = actions,
        permissionsDenied = permissionsDenied && !context.hasCallPermissions(),
        videoPreview = videoPreview,
        modifier = modifier,
    )
}
