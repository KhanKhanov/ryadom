package ru.ryadom.android.volunteer

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.ryadom.android.call.MicrophoneButton
import ru.ryadom.android.ui.BigButton
import ru.ryadom.android.ui.ErrorMessage
import ru.ryadom.android.ui.LiveStatus
import ru.ryadom.android.ui.ScreenHeading
import ru.ryadom.android.ui.SecondaryButton
import ru.ryadom.android.ui.SwitchRow
import ru.ryadom.android.ui.messageRes
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.Language
import ru.ryadom.shared.api.UserProfile
import ru.ryadom.shared.call.CallStatus
import ru.ryadom.shared.call.MicrophoneState
import ru.ryadom.shared.call.status
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError
import ru.ryadom.shared.push.PushStatus
import ru.ryadom.shared.volunteer.VolunteerCall
import ru.ryadom.shared.volunteer.VolunteerNotice
import ru.ryadom.shared.volunteer.VolunteerScreen
import ru.ryadom.shared.volunteer.VolunteerState
import ru.ryadom.android.call.R as CallR
import ru.ryadom.android.ui.R as UiR

/** Действия пользователя на экранах волонтёра. */
interface VolunteerActions {
    fun accept(requestId: String)

    fun skip(requestId: String)

    fun setReady(ready: Boolean)

    fun endCall()

    fun setMicrophoneEnabled(enabled: Boolean)

    fun rate(helped: Boolean)

    fun skipRating()

    fun allowMicrophone()

    fun allowNotifications()

    /** Android 14+: настройки «Вызовы на весь экран» этого приложения. */
    fun allowFullScreen()

    /** Настройки приложения: разрешения, отклонённые навсегда. */
    fun openSettings()

    /** «Мне нужна помощь» — смена роли на «незрячий». */
    fun needHelp()

    fun logout()
}

/** Работает ли на этом телефоне канал push (FCM). */
enum class PushAvailability {
    /** FCM доступен: вызов придёт, даже когда приложение закрыто (если сервер знает устройство). */
    AVAILABLE,

    /** Нет сервисов Google: FCM здесь не работает (в основном телефоны Huawei последних лет). */
    NO_GOOGLE_SERVICES,

    /** В сборке нет настроек Firebase (`ryadom.firebase.*` в `local.properties`). */
    NOT_CONFIGURED,
}

/** Готов ли телефон принимать вызовы: разрешения и push. Собирает платформа ([VolunteerRoute]). */
data class CallReadiness(
    val microphoneGranted: Boolean = true,
    /** Android 13+: разрешение на уведомления; на старых версиях всегда есть. */
    val notificationsGranted: Boolean = true,
    /** Android 14+: разрешение показывать вызов на весь экран; на старых версиях всегда есть. */
    val fullScreenAllowed: Boolean = true,
    val push: PushAvailability = PushAvailability.AVAILABLE,
    val registration: PushStatus = PushStatus.REGISTERED,
    /** Пользователь уже отказывал в микрофоне — системный запрос может не появиться, нужны настройки. */
    val microphoneDeniedBefore: Boolean = false,
    /** То же для уведомлений. */
    val notificationsDeniedBefore: Boolean = false,
)

/** Что показать на экранах волонтёра помимо состояния контроллера. */
data class VolunteerExtras(
    val profile: UserProfile,
    val readiness: CallReadiness = CallReadiness(),
    /** Сейчас время тишины по часовому поясу профиля. */
    val quietNow: Boolean = false,
    /** «Принять» не вышло: нет разрешения на микрофон. */
    val microphoneRefused: Boolean = false,
    /** Смена роли («Мне нужна помощь») — ждём сервер / ошибка; их ведёт AuthController. */
    val roleBusy: Boolean = false,
    val roleError: UserError? = null,
)

/**
 * Экраны волонтёра (docs/ARCHITECTURE.md, раздел 7): главный (готовность, вызовы), звонок, оценка.
 * Правила доступности те же, что у незрячего: строка состояния вверху — live region, ошибки объявляются сразу.
 *
 * @param peerVideo камера незрячего во время звонка (в тестах — пустая).
 */
@Composable
fun VolunteerScreen(
    state: VolunteerState,
    extras: VolunteerExtras,
    actions: VolunteerActions,
    peerVideo: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val call = state.call
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .then(if (call == null) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.screen == VolunteerScreen.HOME) ScreenHeading(stringResource(R.string.volunteer_title))
            val line = statusLine(state)
            LiveStatus(
                text =
                    line.parts
                        .map { stringResource(it) }
                        .joinToString(" ")
                        .ifEmpty { null },
                assertive = line.urgent,
            )
            val error =
                when {
                    extras.microphoneRefused -> R.string.volunteer_accept_needs_microphone
                    state.error != null -> state.error?.messageRes
                    else -> extras.roleError?.messageRes
                }
            ErrorMessage(text = error?.let { stringResource(it) })
            when {
                call != null -> CallContent(call, actions, peerVideo)
                state.finished != null -> RatingContent(state.busy, actions)
                else -> HomeContent(state, extras, actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.HomeContent(
    state: VolunteerState,
    extras: VolunteerExtras,
    actions: VolunteerActions,
) {
    // Вызовы — первыми после строки состояния: их надо найти сразу.
    state.incoming.forEach { request -> IncomingCard(request, state.accepting, actions) }
    if (extras.microphoneRefused) SecondaryButton(stringResource(R.string.volunteer_open_settings), onClick = actions::openSettings)

    val ready = extras.profile.notificationsEnabled
    SwitchRow(text = stringResource(R.string.volunteer_ready), checked = ready, onCheckedChange = actions::setReady, enabled = !state.busy)
    BodyText(if (ready) R.string.volunteer_ready_hint_on else R.string.volunteer_ready_hint_off)

    val dnd = extras.profile.doNotDisturb
    if (dnd.isSet) {
        BodyText(stringResource(R.string.volunteer_quiet_window, dnd.from, dnd.to, extras.profile.timezone))
        if (extras.quietNow) BodyText(stringResource(R.string.volunteer_quiet_now, dnd.to))
    } else {
        BodyText(R.string.volunteer_quiet_none)
    }

    Readiness(extras.readiness, settingsShownAbove = extras.microphoneRefused, actions)

    BodyText(R.string.volunteer_need_help_hint)
    SecondaryButton(stringResource(R.string.volunteer_need_help), onClick = actions::needHelp, enabled = !extras.roleBusy)
    LogoutButton(actions)
}

/** Вызов: «Нужна помощь», язык, «Принять» и «Пропустить». */
@Composable
private fun IncomingCard(
    request: HelpRequest,
    accepting: String?,
    actions: VolunteerActions,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.volunteer_incoming_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.volunteer_incoming_language, stringResource(languageName(request.language))),
                style = MaterialTheme.typography.bodyLarge,
            )
            val thisOne = accepting == request.id
            BigButton(
                text = stringResource(if (thisOne) R.string.volunteer_accepting else R.string.volunteer_accept),
                onClick = { actions.accept(request.id) },
                enabled = accepting == null,
            )
            SecondaryButton(stringResource(R.string.volunteer_skip), onClick = { actions.skip(request.id) }, enabled = accepting == null)
        }
    }
}

/**
 * Что мешает вызовам доходить: разрешения, push. Если всё в порядке — одна строка об этом.
 * @param settingsShownAbove кнопка «Открыть настройки» уже есть выше (после отказа в микрофоне при «Принять»).
 */
@Composable
private fun Readiness(
    readiness: CallReadiness,
    settingsShownAbove: Boolean,
    actions: VolunteerActions,
) {
    val microphone = readiness.microphoneGranted
    val notifications = readiness.notificationsGranted
    if (!microphone) BodyText(R.string.volunteer_microphone_needed)
    if (!notifications) BodyText(R.string.volunteer_notifications_needed)
    // После отказа Android может больше не спрашивать — тогда помогут только настройки. Такая кнопка одна
    // на все разрешения: одинаковые кнопки рядом TalkBack не различит.
    val microphoneInSettings = !microphone && readiness.microphoneDeniedBefore
    val notificationsInSettings = !notifications && readiness.notificationsDeniedBefore
    if (!microphone && !microphoneInSettings) {
        SecondaryButton(stringResource(R.string.volunteer_microphone_allow), onClick = actions::allowMicrophone)
    }
    if (!notifications && !notificationsInSettings) {
        SecondaryButton(stringResource(R.string.volunteer_notifications_allow), onClick = actions::allowNotifications)
    }
    if ((microphoneInSettings || notificationsInSettings) && !settingsShownAbove) {
        SecondaryButton(stringResource(R.string.volunteer_open_settings), onClick = actions::openSettings)
    }
    // Без уведомлений полноэкранного вызова всё равно нет — сначала о них.
    if (notifications && !readiness.fullScreenAllowed) {
        BodyText(R.string.volunteer_full_screen_needed)
        SecondaryButton(stringResource(R.string.volunteer_full_screen_allow), onClick = actions::allowFullScreen)
    }
    val push =
        when (readiness.push) {
            PushAvailability.NO_GOOGLE_SERVICES -> R.string.volunteer_push_no_services
            PushAvailability.NOT_CONFIGURED -> R.string.volunteer_push_not_configured
            PushAvailability.AVAILABLE -> pushStatusText(readiness)
        }
    push?.let { BodyText(it) }
}

/** Текст о push, когда FCM на телефоне есть. Пока устройство регистрируется, говорить нечего. */
@StringRes
private fun pushStatusText(readiness: CallReadiness): Int? =
    when (readiness.registration) {
        PushStatus.FAILED -> R.string.volunteer_push_failed
        PushStatus.REGISTERED -> if (readiness.notificationsGranted) R.string.volunteer_push_ready else null
        PushStatus.WAITING_FOR_TOKEN, PushStatus.REGISTERING -> null
    }

@Composable
private fun LogoutButton(actions: VolunteerActions) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    SecondaryButton(text = stringResource(UiR.string.logout), onClick = { confirm = true })
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(UiR.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.volunteer_logout_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.logout()
                }) { Text(stringResource(UiR.string.logout)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

@Composable
private fun ColumnScope.CallContent(
    call: VolunteerCall,
    actions: VolunteerActions,
    peerVideo: @Composable (Modifier) -> Unit,
) {
    // Видео — на всё свободное место, кадр целиком. Фон тёмный, как у видео.
    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        if (!call.ending) peerVideo(Modifier.fillMaxSize())
        if (!call.call.peerVideo) {
            Text(
                text = stringResource(R.string.volunteer_call_no_video),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    // Пока звонок завершается, кнопок нет: звук уже выключен, а «Завершаем звонок…» говорит строка состояния.
    if (call.ending) return
    if (call.call.microphone == MicrophoneState.BLOCKED) {
        SecondaryButton(stringResource(R.string.volunteer_open_settings), onClick = actions::openSettings)
    }
    MicrophoneButton(microphone = call.call.microphone, onSetEnabled = actions::setMicrophoneEnabled)
    BigButton(
        text = stringResource(CallR.string.call_end),
        onClick = actions::endCall,
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
    actions: VolunteerActions,
) {
    BigButton(
        text = stringResource(R.string.volunteer_rating_yes),
        onClick = { actions.rate(helped = true) },
        enabled = !busy,
        modifier = Modifier.weight(1f),
    )
    BigButton(
        text = stringResource(R.string.volunteer_rating_no),
        onClick = { actions.rate(helped = false) },
        enabled = !busy,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    )
    SecondaryButton(stringResource(R.string.volunteer_rating_skip), onClick = actions::skipRating, enabled = !busy)
}

@Composable
private fun BodyText(
    @StringRes text: Int,
) = BodyText(stringResource(text))

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
}

@StringRes
private fun languageName(language: Language): Int =
    when (language) {
        Language.RU -> R.string.volunteer_language_ru
        Language.EN -> R.string.volunteer_language_en
    }

/**
 * Строка состояния: фразы (строковые ресурсы) по порядку чтения. [urgent] — объявить сразу,
 * прервав текущую речь TalkBack (входящий вызов, звонок под угрозой).
 */
internal data class StatusLine(
    val parts: List<Int>,
    val urgent: Boolean,
)

/** Что сказать в строке состояния: последнее сообщение о вызовах, состояние звонка, нет ли связи с сервером. */
internal fun statusLine(state: VolunteerState): StatusLine {
    val parts = mutableListOf<Int>()
    var urgent = false
    val call = state.call
    when (state.screen) {
        VolunteerScreen.HOME -> {
            val notice = state.notice?.kind
            // «Входящий вызов» — пока он звонит; «собеседник завершил» — на экране оценки, а не здесь.
            if (notice != null && notice != VolunteerNotice.CALL_ENDED_BY_PEER && (notice != VolunteerNotice.INCOMING || state.ringing)) {
                parts += noticeText(notice)
                urgent = notice == VolunteerNotice.INCOMING
            }
        }

        VolunteerScreen.CALL -> {
            if (call == null || call.ending) {
                parts += CallR.string.call_ending
            } else {
                parts += callText(call.call.status)
                when (call.call.microphone) {
                    MicrophoneState.BLOCKED -> parts += R.string.volunteer_call_microphone_blocked
                    MicrophoneState.MUTED -> parts += CallR.string.call_microphone_muted
                    MicrophoneState.STARTING, MicrophoneState.ON -> Unit
                }
                urgent = call.call.status in URGENT_CALL_STATUSES || call.call.microphone == MicrophoneState.BLOCKED
            }
        }

        VolunteerScreen.RATING -> {
            if (state.finished?.endedByPeer == true) parts += R.string.volunteer_notice_call_ended_by_peer
            parts += R.string.volunteer_rating_question
        }
    }
    // Пока волонтёр отвечает на «Удалось помочь?», мог прийти новый вызов.
    if (state.screen == VolunteerScreen.RATING && state.ringing) {
        parts += R.string.volunteer_notice_incoming
        urgent = true
    }
    // Во время звонка связь с сервером событий не нужна: видео и звук идут через LiveKit.
    if (state.connection == RealtimeStatus.RECONNECTING && call == null) parts += UiR.string.connection_lost
    return StatusLine(parts, urgent)
}

private val URGENT_CALL_STATUSES = setOf(CallStatus.PEER_LEFT, CallStatus.DISCONNECTED)

@StringRes
private fun callText(status: CallStatus): Int =
    when (status) {
        CallStatus.CONNECTING -> R.string.volunteer_call_connecting
        CallStatus.WAITING_FOR_PEER -> R.string.volunteer_call_waiting
        CallStatus.ACTIVE -> R.string.volunteer_call_active
        CallStatus.PEER_LEFT -> R.string.volunteer_call_peer_left
        CallStatus.RECONNECTING -> R.string.volunteer_call_reconnecting
        CallStatus.DISCONNECTED -> R.string.volunteer_call_disconnected
    }

@StringRes
internal fun noticeText(notice: VolunteerNotice): Int =
    when (notice) {
        VolunteerNotice.INCOMING -> R.string.volunteer_notice_incoming
        VolunteerNotice.TAKEN -> R.string.volunteer_notice_taken
        VolunteerNotice.ACCEPTED_ELSEWHERE -> R.string.volunteer_notice_accepted_elsewhere
        VolunteerNotice.CANCELLED -> R.string.volunteer_notice_cancelled
        VolunteerNotice.NO_ANSWER -> R.string.volunteer_notice_no_answer
        VolunteerNotice.TOO_LATE_TAKEN -> R.string.volunteer_notice_too_late_taken
        VolunteerNotice.TOO_LATE_CLOSED -> R.string.volunteer_notice_too_late_closed
        VolunteerNotice.ALREADY_IN_CALL -> R.string.volunteer_notice_already_in_call
        VolunteerNotice.CALL_ENDED_BY_PEER -> R.string.volunteer_notice_call_ended_by_peer
        VolunteerNotice.CALL_ENDED -> R.string.volunteer_notice_call_ended
        VolunteerNotice.CALL_NOT_STARTED -> R.string.volunteer_notice_call_not_started
        VolunteerNotice.READY_ON -> R.string.volunteer_notice_ready_on
        VolunteerNotice.READY_OFF -> R.string.volunteer_notice_ready_off
        VolunteerNotice.RATED -> R.string.volunteer_notice_rated
    }
