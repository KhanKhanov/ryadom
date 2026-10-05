package ru.ryadom.android.volunteer

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.ryadom.android.call.CallForegroundService
import ru.ryadom.android.call.CallServiceStatus
import ru.ryadom.shared.volunteer.VolunteerController
import ru.ryadom.shared.volunteer.VolunteerState

/** Приложение на экране (хотя бы одно окно видно). В фоне вызовы приходят уведомлением. */
fun appVisibility(): Flow<Boolean> =
    ProcessLifecycleOwner
        .get()
        .lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .distinctUntilChanged()

/**
 * То, что должно работать и без экрана волонтёра: звонок входящего вызова (в приложении — [Ringer],
 * в фоне — уведомление), foreground service во время звонка, уборка уведомлений о закрытых вызовах.
 * Поэтому это не часть экрана, а подписка на состояние контроллера на всё время, пока волонтёр вошёл.
 *
 * @param appVisible приложение на экране ([appVisibility]; в тестах — поддельный поток).
 */
fun CoroutineScope.launchVolunteerSideEffects(
    context: Context,
    controller: VolunteerController,
    appVisible: Flow<Boolean> = appVisibility(),
): Job =
    launch {
        val appContext = context.applicationContext
        val ringer = Ringer(appContext)
        launch {
            controller.state
                .map(::serviceStatus)
                .distinctUntilChanged()
                .collect { CallForegroundService.update(appContext, it) }
        }
        launch {
            combine(controller.state, appVisible) { state, visible -> state.ringing && visible }
                .distinctUntilChanged()
                .collect { if (it) ringer.start() else ringer.stop() }
        }
        launch {
            var shown = emptySet<String>()
            combine(controller.state, appVisible, ::notifications).distinctUntilChanged().collect { wanted ->
                if (wanted == null) {
                    // Приложение на экране (вызов звонит само) или идёт звонок — уведомления о вызовах не нужны,
                    // в том числе показанные по push.
                    IncomingCallNotifications.cancelAllExcept(appContext)
                    shown = emptySet()
                } else {
                    (wanted - shown).forEach { IncomingCallNotifications.show(appContext, it) }
                    // Вызов исчез из списка (принят другим, отменён, пропущен) — уведомление о нём тоже.
                    (shown - wanted).forEach { IncomingCallNotifications.cancel(appContext, it) }
                    shown = wanted
                }
            }
        }
        launch {
            // После сверки с сервером: уведомления о вызовах, которые больше не ждут ответа (например,
            // показанные по push уже после того, как вызов приняли), убрать.
            controller.waitingSynced.collect { waiting -> IncomingCallNotifications.cancelAllExcept(appContext, waiting) }
        }
        try {
            awaitCancellation()
        } finally {
            // Волонтёр вышел или сменил роль: звонить больше незачем.
            ringer.stop()
            IncomingCallNotifications.cancelAllExcept(appContext)
            CallForegroundService.update(appContext, null)
        }
    }

/** Foreground service нужен, пока идёт звонок (и волонтёр его не завершает). */
internal fun serviceStatus(state: VolunteerState): CallServiceStatus? = state.call?.takeUnless { it.ending }?.let { CallServiceStatus.CALL }

/**
 * О каких вызовах должны быть уведомления: в фоне — о каждом, который ждёт ответа.
 * `null` — ни о каких: приложение на экране (вызов звонит само) или идёт звонок.
 */
internal fun notifications(
    state: VolunteerState,
    appVisible: Boolean,
): Set<String>? = if (appVisible || state.call != null) null else state.incoming.map { it.id }.toSet()
