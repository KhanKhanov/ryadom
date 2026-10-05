package ru.ryadom.android.volunteer

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import ru.ryadom.android.call.R as CallR

/**
 * Уведомление о входящем вызове (docs/ARCHITECTURE.md, раздел 7) — когда приложение не на экране.
 * Звонит мелодией звонка, пока его не уберут; на заблокированном телефоне открывает приложение на весь
 * экран (full-screen intent), на разблокированном — всплывает с кнопками «Принять» и «Пропустить».
 * Одно уведомление на вызов: тег — id запроса.
 *
 * В уведомлении нет данных о незрячем — только «нужна помощь» (оно видно и на экране блокировки).
 */
object IncomingCallNotifications {
    /** Нажатие на уведомление (или полноэкранный показ): открыть приложение с вызовами. */
    const val ACTION_OPEN = "ru.ryadom.action.OPEN_INCOMING_CALL"

    /** «Принять» в уведомлении: открыть приложение и принять вызов [EXTRA_REQUEST_ID]. */
    const val ACTION_ACCEPT = "ru.ryadom.action.ACCEPT_INCOMING_CALL"

    /** «Пропустить» или уведомление смахнули. */
    const val ACTION_SKIP = "ru.ryadom.action.SKIP_INCOMING_CALL"

    const val EXTRA_REQUEST_ID = "ru.ryadom.extra.REQUEST_ID"

    /**
     * Через сколько уведомление убирается само, если о закрытии вызова не пришло ни события, ни push:
     * поиск волонтёра длится около минуты, как и у сайта (`web/src/push/workerHandlers.ts`).
     */
    const val TIMEOUT_MS = 120_000L

    private const val CHANNEL_ID = "incoming_calls"

    /** id уведомления (вместе с тегом); 1 — у CallForegroundService. */
    private const val NOTIFICATION_ID = 2
    private const val TAG = "IncomingCall"

    /** Показать (или обновить, не звоня заново) уведомление о вызове [requestId]. */
    fun show(
        context: Context,
        requestId: String,
    ) {
        // Без разрешения (Android 13+) уведомление не показать: кабинет волонтёра просит его заранее.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val open = activityIntent(context, ACTION_OPEN, requestId) ?: return
        val accept = activityIntent(context, ACTION_ACCEPT, requestId) ?: return
        val skip = skipIntent(context, requestId)
        val caller =
            Person
                .Builder()
                .setName(context.getString(R.string.incoming_notification_title))
                .setImportant(true)
                .build()
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(CallR.drawable.ic_call_notification)
                .setContentTitle(context.getString(R.string.incoming_notification_title))
                .setContentText(context.getString(R.string.incoming_notification_text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                // Полноэкранный показ на заблокированном телефоне. Если Android 14+ его не разрешает,
                // система покажет обычное всплывающее уведомление (а кабинет объясняет, как разрешить).
                .setFullScreenIntent(open, true)
                .setDeleteIntent(skip)
                .setTimeoutAfter(TIMEOUT_MS)
                .setAutoCancel(true)
                // Обновление того же вызова (пришёл и push, и событие) не начинает звонок заново.
                .setOnlyAlertOnce(true)
        val manager = NotificationManagerCompat.from(context)
        val callStyle = builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, skip, accept)).build().ringing()
        try {
            manager.notify(requestId, NOTIFICATION_ID, callStyle)
        } catch (e: IllegalArgumentException) {
            // Оформление звонка Android принимает только с полноэкранным показом; если его запретили —
            // обычное уведомление с теми же кнопками.
            Log.w(TAG, "Call style rejected: ${e::class.java.simpleName}")
            val plain =
                builder
                    .setStyle(null)
                    .addAction(0, context.getString(R.string.incoming_notification_skip), skip)
                    .addAction(0, context.getString(R.string.incoming_notification_accept), accept)
                    .build()
                    .ringing()
            manager.notify(requestId, NOTIFICATION_ID, plain)
        }
    }

    /** Убрать уведомление о вызове [requestId] (принят, отменён, пропущен). */
    fun cancel(
        context: Context,
        requestId: String,
    ) {
        NotificationManagerCompat.from(context).cancel(requestId, NOTIFICATION_ID)
    }

    /** Убрать уведомления обо всех вызовах, кроме [keep]. */
    fun cancelAllExcept(
        context: Context,
        keep: Set<String> = emptySet(),
    ) {
        (shown(context) - keep).forEach { cancel(context, it) }
    }

    /** id запросов, о которых сейчас есть уведомления. */
    fun shown(context: Context): Set<String> =
        NotificationManagerCompat
            .from(context)
            .activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.tag != null }
            .mapNotNull { it.tag }
            .toSet()

    /**
     * Можно ли показывать вызов на весь экран. С Android 14 это отдельное разрешение: Google Play даёт его
     * только звонилкам и будильникам, а пользователь может отозвать его в настройках.
     */
    fun canUseFullScreen(context: Context): Boolean = NotificationManagerCompat.from(context).canUseFullScreenIntent()

    /** Звонить, пока уведомление не уберут, а не один раз. */
    private fun Notification.ringing(): Notification = apply { flags = flags or Notification.FLAG_INSISTENT }

    private fun activityIntent(
        context: Context,
        action: String,
        requestId: String,
    ): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.action = action
        launch.putExtra(EXTRA_REQUEST_ID, requestId)
        // Уже открытое приложение получает вызов в тот же экран, а не открывается второй раз.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode(action, requestId),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun skipIntent(
        context: Context,
        requestId: String,
    ): PendingIntent {
        val intent =
            Intent(context, IncomingCallActionReceiver::class.java)
                .setAction(ACTION_SKIP)
                .putExtra(EXTRA_REQUEST_ID, requestId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(ACTION_SKIP, requestId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Свой код у каждого действия каждого вызова: иначе Android заменил бы одно намерение другим. */
    private fun requestCode(
        action: String,
        requestId: String,
    ) = (action + requestId).hashCode()

    private fun createChannel(context: Context) {
        val ringtone =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        val channel =
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.incoming_channel))
                .setDescription(context.getString(R.string.incoming_channel_description))
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ringtone)
                .setVibrationEnabled(true)
                .setVibrationPattern(Ringer.VIBRATION_PATTERN)
                .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

/** Приложение, в котором живёт контроллер волонтёра (`RyadomApplication`): ему передаются действия из уведомления. */
interface VolunteerHost {
    /** «Пропустить» в уведомлении о вызове [requestId] (или уведомление смахнули). */
    fun skipIncomingCall(requestId: String)
}

/** «Пропустить» в уведомлении о вызове: вызов исчезает только на этом телефоне, как и в приложении. */
class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != IncomingCallNotifications.ACTION_SKIP) return
        val requestId = intent.getStringExtra(IncomingCallNotifications.EXTRA_REQUEST_ID) ?: return
        IncomingCallNotifications.cancel(context, requestId)
        (context.applicationContext as? VolunteerHost)?.skipIncomingCall(requestId)
    }
}
