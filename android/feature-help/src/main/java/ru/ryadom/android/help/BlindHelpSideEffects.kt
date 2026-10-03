package ru.ryadom.android.help

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.ryadom.android.call.CallForegroundService
import ru.ryadom.android.call.CallServiceStatus
import ru.ryadom.android.ui.HapticPattern
import ru.ryadom.android.ui.vibrate
import ru.ryadom.shared.call.CallStatus
import ru.ryadom.shared.call.status
import ru.ryadom.shared.help.BlindHelpController
import ru.ryadom.shared.help.BlindScreen
import ru.ryadom.shared.help.BlindState

/**
 * То, что должно работать и при выключенном экране: вибрация при смене статуса и foreground service
 * на время поиска и звонка. Поэтому это не часть экрана, а подписка на состояние контроллера
 * на всё время, пока пользователь вошёл.
 */
fun CoroutineScope.launchBlindHelpSideEffects(
    context: Context,
    controller: BlindHelpController,
) = launch {
    val appContext = context.applicationContext
    var previous: BlindState? = null
    controller.state.collect { state ->
        val before = previous
        previous = state
        if (before == null) {
            CallForegroundService.update(appContext, serviceStatus(state.screen))
            return@collect
        }
        val service = serviceStatus(state.screen)
        if (serviceStatus(before.screen) != service) CallForegroundService.update(appContext, service)
        hapticFor(before, state)?.let { appContext.vibrate(it) }
    }
}

/** Foreground service нужен, пока идёт поиск или звонок. */
internal fun serviceStatus(screen: BlindScreen): CallServiceStatus? =
    when (screen) {
        is BlindScreen.Searching -> CallServiceStatus.SEARCHING
        is BlindScreen.Call -> CallServiceStatus.CALL
        else -> null
    }

/**
 * Какой вибрацией отметить переход из [before] в [after]: вибрация — при каждом изменении статуса,
 * то есть строки состояния ([statusLine]), даже если звук выключен или TalkBack говорит другое.
 * `null` — без вибрации: статус не изменился, строка опустела (сказать нечего) или это восстановление
 * после запуска приложения.
 */
internal fun hapticFor(
    before: BlindState,
    after: BlindState,
): HapticPattern? {
    if (before.screen is BlindScreen.Loading) return null
    val line = statusLine(after)
    if (line.parts.isEmpty() || line == statusLine(before)) return null
    val from = before.screen
    val to = after.screen
    val fromCall = (from as? BlindScreen.Call)?.call?.status
    val toCall = (to as? BlindScreen.Call)?.call?.status
    val requestWasOpen = from is BlindScreen.Searching || from is BlindScreen.Call
    return when {
        // Волонтёр найден или появился в звонке — хорошая новость.
        to is BlindScreen.Call && from !is BlindScreen.Call -> HapticPattern.SUCCESS

        toCall == CallStatus.ACTIVE && fromCall != null && fromCall != CallStatus.ACTIVE -> HapticPattern.SUCCESS

        // Волонтёр пропал или связь потеряна — что-то закончилось.
        toCall in LOST && fromCall != null && fromCall !in LOST -> HapticPattern.END

        // Поиск или звонок закончились.
        requestWasOpen && (to is BlindScreen.Ready || to is BlindScreen.Rating) -> HapticPattern.END

        else -> HapticPattern.TICK
    }
}

/** Звонок под угрозой: волонтёр пропал или связь потеряна. */
private val LOST = setOf(CallStatus.PEER_LEFT, CallStatus.DISCONNECTED)
