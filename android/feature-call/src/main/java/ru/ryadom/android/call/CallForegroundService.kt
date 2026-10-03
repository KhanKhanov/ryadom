package ru.ryadom.android.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Что сейчас происходит — от этого зависит текст уведомления. */
enum class CallServiceStatus {
    /** Ищем волонтёра (у незрячего). */
    SEARCHING,

    /** Идёт звонок. */
    CALL,
}

/**
 * Foreground service на время поиска и звонка (docs/ARCHITECTURE.md, раздел 7, типы camera и microphone).
 * Пока он работает, система не останавливает приложение и даёт ему камеру и микрофон, даже если экран
 * погас или пользователь переключился на другое приложение. Уведомление показывает, что идёт звонок.
 *
 * Сам звонок живёт не здесь, а в контроллере (shared): сервис только держит приложение «на переднем плане».
 * Управление — [update]: передайте статус, когда начался поиск или звонок, и `null`, когда всё закончилось.
 */
class CallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // startForeground нужно вызвать сразу после запуска, иначе система завершит приложение.
        if (!showNotification(status.value ?: CallServiceStatus.SEARCHING)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Повторный запуск сервиса (новый поиск после звонка) — подписка на статус одна.
        collector?.cancel()
        collector =
            scope.launch {
                status.collect { current ->
                    if (current == null) {
                        ServiceCompat.stopForeground(this@CallForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        showNotification(current)
                    }
                }
            }
        // Если система всё же остановит приложение, сервис без звонка перезапускать незачем.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showNotification(status: CallServiceStatus): Boolean {
        val types = foregroundTypes(this)
        if (types == 0) return false
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(status), types)
            true
        } catch (e: RuntimeException) {
            // Android 12+ запрещает запуск из фона, Android 14+ — без разрешений камеры и микрофона.
            Log.w(TAG, "Cannot start foreground service: ${e::class.java.simpleName}")
            false
        }
    }

    private fun notification(status: CallServiceStatus): Notification {
        createChannel(this)
        val text =
            when (status) {
                CallServiceStatus.SEARCHING -> R.string.call_notification_searching
                CallServiceStatus.CALL -> R.string.call_notification_call
            }
        // Нажатие на уведомление открывает приложение, не создавая второй экран.
        val openApp =
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_notification)
            .setContentTitle(getString(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val CHANNEL_ID = "call"
        private const val NOTIFICATION_ID = 1

        /** Текущий статус; `null` — сервис не нужен. */
        private val status = MutableStateFlow<CallServiceStatus?>(null)

        /**
         * Поиск или звонок начался ([newStatus] не `null`) или закончился (`null`).
         * Запускать сервис можно, только пока приложение на экране (ограничение Android 12+),
         * поэтому вызывайте это сразу после действия пользователя или при открытии приложения.
         */
        fun update(
            context: Context,
            newStatus: CallServiceStatus?,
        ) {
            val wasRunning = status.value != null
            status.value = newStatus
            if (newStatus == null || wasRunning || foregroundTypes(context) == 0) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
            } catch (e: RuntimeException) {
                // Приложение в фоне (Android 12+): попробуем, когда пользователь его откроет.
                status.value = null
                Log.w(TAG, "Cannot start foreground service: ${e::class.java.simpleName}")
            }
        }

        /**
         * Типы foreground service по выданным разрешениям: Android 14+ не даёт объявить тип camera
         * без разрешения на камеру. 0 — нет ни одного разрешения, сервис не запустить.
         *
         * Константы типов из Android 11 подставляются в код при сборке, а на Android 8–10 лишние типы
         * отбрасывает ServiceCompat — поэтому предупреждение InlinedApi здесь ложное.
         */
        @SuppressLint("InlinedApi")
        internal fun foregroundTypes(context: Context): Int {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            var types = 0
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            return types
        }

        private fun createChannel(context: Context) {
            val channel =
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.call_notification_channel))
                    .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }
}
