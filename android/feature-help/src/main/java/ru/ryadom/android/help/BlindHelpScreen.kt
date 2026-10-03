package ru.ryadom.android.help

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ru.ryadom.android.call.MicrophoneButton
import ru.ryadom.android.ui.BigButton
import ru.ryadom.android.ui.ErrorMessage
import ru.ryadom.android.ui.LiveStatus
import ru.ryadom.android.ui.SecondaryButton
import ru.ryadom.android.ui.messageRes
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.CallStatus
import ru.ryadom.shared.call.CameraState
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.status
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.help.BlindScreen
import ru.ryadom.shared.help.BlindState
import ru.ryadom.shared.help.HelpOutcome
import ru.ryadom.android.call.R as CallR
import ru.ryadom.android.ui.R as UiR

/** Действия пользователя на экранах незрячего. */
interface BlindHelpActions {
    fun requestHelp()

    fun cancelSearch()

    fun endCall()

    fun setMicrophoneEnabled(enabled: Boolean)

    fun rate(helped: Boolean)

    fun skipRating()

    /** Открыть настройки приложения, чтобы разрешить камеру и микрофон. */
    fun openSettings()

    fun logout()
}

/**
 * Экраны незрячего (docs/ARCHITECTURE.md, раздел 7): одна большая кнопка на весь экран,
 * голосовое объявление (live region) при каждой смене статуса.
 *
 * Строка состояния вверху одна на все состояния ([statusLine]): TalkBack объявляет её изменения, а при свайпах
 * она читается первой, затем — главная кнопка. Второй live region рядом TalkBack может пропустить,
 * поэтому всё, что нужно объявить (нет связи, нет доступа к камере), собирается в эту строку.
 *
 * @param permissionsDenied пользователь не дал доступ к камере или микрофону — без них звонок не начать.
 * @param videoPreview изображение своей камеры во время звонка (в тестах — пустое).
 */
@Composable
fun BlindHelpScreen(
    state: BlindState,
    actions: BlindHelpActions,
    permissionsDenied: Boolean,
    videoPreview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen = state.screen
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val line = statusLine(state, permissionsDenied)
            LiveStatus(
                text =
                    line.parts
                        .map { stringResource(it) }
                        .joinToString(" ")
                        .ifEmpty { null },
                assertive = line.urgent,
            )
            ErrorMessage(text = state.error?.let { stringResource(it.messageRes) })
            when (screen) {
                BlindScreen.Loading -> LoadingContent()
                is BlindScreen.Ready -> ReadyContent(state.busy, permissionsDenied, actions)
                is BlindScreen.Searching -> SearchingContent(state.busy, actions)
                is BlindScreen.Call -> CallContent(screen, actions, videoPreview)
                is BlindScreen.Rating -> RatingContent(state.busy, actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.LoadingContent() {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Что происходит, говорит строка состояния; индикатор TalkBack не читает.
        CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun ColumnScope.ReadyContent(
    busy: Boolean,
    permissionsDenied: Boolean,
    actions: BlindHelpActions,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    // Что не так с разрешениями, говорит строка состояния.
    if (permissionsDenied) SecondaryButton(text = stringResource(R.string.help_open_settings), onClick = actions::openSettings)
    BigButton(
        text = stringResource(R.string.help_request),
        onClick = actions::requestHelp,
        enabled = !busy,
        modifier = Modifier.weight(1f),
    )
    SecondaryButton(text = stringResource(UiR.string.logout), onClick = { confirmLogout = true }, enabled = !busy)
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(UiR.string.logout_confirm_title)) },
            text = { Text(stringResource(UiR.string.logout_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    actions.logout()
                }) { Text(stringResource(UiR.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(UiR.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ColumnScope.SearchingContent(
    busy: Boolean,
    actions: BlindHelpActions,
) {
    BigButton(
        text = stringResource(R.string.help_cancel),
        onClick = actions::cancelSearch,
        enabled = !busy,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    )
}

@Composable
private fun ColumnScope.CallContent(
    call: BlindScreen.Call,
    actions: BlindHelpActions,
    videoPreview: @Composable (Modifier) -> Unit,
) {
    videoPreview(Modifier.weight(1f).fillMaxWidth())
    // Пока звонок завершается, кнопок нет: камера и микрофон уже выключены, а «Завершаем звонок…» говорит строка состояния.
    if (call.ending) return
    // Нет доступа к камере или микрофону (об этом говорит строка состояния): разрешить в настройках.
    // Когда пользователь вернётся, BlindHelpRoute попробует включить их снова.
    if (call.call.hasBlockedDevice) {
        SecondaryButton(text = stringResource(R.string.help_open_settings), onClick = actions::openSettings)
    }
    MicrophoneButton(microphone = call.call.microphone, onSetEnabled = actions::setMicrophoneEnabled)
    // Не на весь экран: незрячий держит телефон и наводит камеру — случайное касание не должно завершать звонок.
    BigButton(
        text = stringResource(CallR.string.call_end),
        onClick = actions::endCall,
        modifier = Modifier.height(120.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
    )
}

@Composable
private fun ColumnScope.RatingContent(
    busy: Boolean,
    actions: BlindHelpActions,
) {
    BigButton(
        text = stringResource(R.string.help_rating_yes),
        onClick = { actions.rate(helped = true) },
        enabled = !busy,
        modifier = Modifier.weight(1f),
    )
    BigButton(
        text = stringResource(R.string.help_rating_no),
        onClick = { actions.rate(helped = false) },
        enabled = !busy,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    )
    SecondaryButton(text = stringResource(R.string.help_rating_skip), onClick = actions::skipRating, enabled = !busy)
}

/**
 * Строка состояния: фразы (строковые ресурсы) по порядку чтения. Пустая — сказать нечего
 * (главный экран без новостей). [urgent] — объявить сразу, прервав текущую речь TalkBack.
 */
internal data class StatusLine(
    val parts: List<Int>,
    val urgent: Boolean,
)

/**
 * Что сказать в строке состояния: главное о запросе или звонке, затем — нет ли доступа к камере
 * и микрофону и связи с сервером. Каждое изменение строки объявляется голосом и вибрацией
 * ([hapticFor]), поэтому сюда попадает всё, о чём незрячий должен узнать.
 *
 * @param permissionsDenied пользователь не дал доступ к камере или микрофону (до начала звонка).
 */
internal fun statusLine(
    state: BlindState,
    permissionsDenied: Boolean = false,
): StatusLine {
    val parts = mutableListOf<Int>()
    var urgent = false
    when (val screen = state.screen) {
        BlindScreen.Loading -> {
            parts += UiR.string.loading
        }

        is BlindScreen.Ready -> {
            screen.outcome?.let { parts += outcomeText(it) }
            if (permissionsDenied) parts += R.string.help_permissions_needed
        }

        is BlindScreen.Searching -> {
            parts += R.string.help_searching
        }

        is BlindScreen.Rating -> {
            parts += R.string.help_rating_question
        }

        is BlindScreen.Call -> {
            if (screen.ending) {
                parts += CallR.string.call_ending
            } else {
                val call = screen.call
                parts += callText(call)
                if (call.camera == CameraState.BLOCKED) parts += R.string.help_camera_blocked
                when (call.microphone) {
                    MicrophoneState.BLOCKED -> parts += R.string.help_microphone_blocked
                    MicrophoneState.MUTED -> parts += CallR.string.call_microphone_muted
                    MicrophoneState.STARTING, MicrophoneState.ON -> Unit
                }
                // Важное — сразу: волонтёр найден или пропал, звонок прервался, волонтёр вас не видит или не слышит.
                urgent = call.status in URGENT_CALL_STATUSES || call.hasBlockedDevice
            }
        }
    }
    // Во время звонка связь с сервером событий не нужна: видео и звук идут через LiveKit.
    if (state.connection == RealtimeStatus.RECONNECTING && state.screen !is BlindScreen.Call) parts += UiR.string.connection_lost
    return StatusLine(parts, urgent)
}

private val URGENT_CALL_STATUSES = setOf(CallStatus.CONNECTING, CallStatus.PEER_LEFT, CallStatus.DISCONNECTED)

/** Камера или микрофон недоступны: нет разрешения или их занимает другое приложение. */
internal val CallState.hasBlockedDevice: Boolean
    get() = camera == CameraState.BLOCKED || microphone == MicrophoneState.BLOCKED

@StringRes
private fun callText(call: CallState): Int =
    when (call.status) {
        CallStatus.CONNECTING -> R.string.help_call_connecting

        CallStatus.WAITING_FOR_PEER -> R.string.help_call_waiting

        // «Наведите камеру» — только если волонтёр видит изображение.
        CallStatus.ACTIVE -> if (call.camera == CameraState.BLOCKED) R.string.help_call_active_no_camera else R.string.help_call_active

        CallStatus.PEER_LEFT -> R.string.help_call_volunteer_left

        CallStatus.RECONNECTING -> R.string.help_call_reconnecting

        CallStatus.DISCONNECTED -> R.string.help_call_disconnected
    }

@StringRes
private fun outcomeText(outcome: HelpOutcome): Int =
    when (outcome) {
        HelpOutcome.NO_ANSWER -> R.string.help_no_answer
        HelpOutcome.NO_ANSWER_AT_NIGHT -> R.string.help_no_answer_night
        HelpOutcome.CANCELLED -> R.string.help_cancelled
        HelpOutcome.VOLUNTEER_LEFT -> R.string.help_volunteer_left_before_call
        HelpOutcome.CALL_ENDED -> R.string.help_call_ended
        HelpOutcome.RATED -> R.string.help_rating_thanks
    }
