package ru.ryadom.android.volunteer

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Пока этот элемент на экране, окно нельзя снять снимком или записью экрана системными средствами
 * (FLAG_SECURE), и в списке недавних приложений вместо него пусто. Защита незрячих (docs/ARCHITECTURE.md,
 * раздел 9): волонтёр видит то, что попало в кадр, — карту, документы, конверт с адресом.
 *
 * Флаг ставится на окно из экрана-маршрута (VolunteerRoute), а сам экран звонка остаётся чистым отображением.
 * UI-тестам доступности флаг не мешает: на этапе 6 проверено, что ATF на эмуляторе (Android 15) находит
 * плохой контраст и в окне с FLAG_SECURE — снимки для тестов делаются с правами инструментирования.
 */
@Composable
fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Показывать приложение поверх экрана блокировки и включать экран, пока [enabled] — пока вызов звонит
 * или идёт звонок, как у приложения «Телефон». Без вызова заблокированный телефон снова просит разблокировку.
 */
@Composable
fun ShowOverLockScreen(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, enabled) {
        activity.showOverLockScreen(enabled)
        onDispose { activity.showOverLockScreen(false) }
    }
}

/** То же для Activity: приложение открыто из уведомления о вызове — показать его сразу, ещё до загрузки экранов. */
fun Activity.showOverLockScreen(enabled: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(enabled)
        setTurnScreenOn(enabled)
    } else {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (enabled) window.addFlags(flags) else window.clearFlags(flags)
    }
}
