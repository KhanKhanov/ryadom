package ru.ryadom.android.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Вибрация при смене статуса (docs/ARCHITECTURE.md, раздел 7): незрячий чувствует, что что-то
 * произошло, даже если звук выключен или TalkBack говорит другое. Разные события — разный рисунок.
 */
enum class HapticPattern(
    /** Чередование «пауза, вибрация» в миллисекундах, как в [VibrationEffect.createWaveform]. */
    internal val timings: LongArray,
) {
    /** Короткий толчок: действие принято (поиск начался). */
    TICK(longArrayOf(0, 80)),

    /** Два толчка: хорошая новость (волонтёр найден). */
    SUCCESS(longArrayOf(0, 150, 120, 150)),

    /** Долгая вибрация: что-то закончилось (звонок завершён, никто не ответил). */
    END(longArrayOf(0, 450)),
}

fun Context.vibrate(pattern: HapticPattern) {
    val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
    if (vibrator == null || !vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(pattern.timings, -1))
}
