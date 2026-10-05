package ru.ryadom.android.volunteer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Звонок входящего вызова, пока приложение на экране (в фоне звонит уведомление): мелодия звонка
 * телефона по кругу и вибрация. Режимы телефона соблюдаются: «Без звука» — тишина, «Вибрация» — только вибрация.
 */
class Ringer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var vibrating = false

    fun start() {
        if (player != null || vibrating) return
        val mode = appContext.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_SILENT) return
        vibrator()?.takeIf { it.hasVibrator() }?.let {
            it.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            vibrating = true
        }
        if (mode == AudioManager.RINGER_MODE_NORMAL) player = play()
    }

    fun stop() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        if (vibrating) vibrator()?.cancel()
        vibrating = false
    }

    private fun play(): MediaPlayer? {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return null
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(appContext, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            player
        } catch (e: Exception) {
            // Мелодии нет или её не прочитать — остаётся вибрация и голосовое объявление.
            Log.w(TAG, "Cannot play the ringtone: ${e::class.java.simpleName}")
            player.release()
            null
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            appContext.getSystemService(Vibrator::class.java)
        }

    companion object {
        private const val TAG = "Ringer"

        /** Вибрация звонка: секунда вибрации, секунда паузы — по кругу. */
        val VIBRATION_PATTERN = longArrayOf(0, 1_000, 1_000)
    }
}
