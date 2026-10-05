package ru.ryadom.android.push

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ru.ryadom.android.BuildConfig
import ru.ryadom.android.RyadomApplication
import ru.ryadom.android.volunteer.PushAvailability
import ru.ryadom.shared.api.PushMessage

/**
 * Firebase Cloud Messaging на этом телефоне (docs/ARCHITECTURE.md, раздел 7) — только для волонтёра:
 * незрячий сам начинает звонок, и его телефон в FCM не регистрируется.
 *
 * Адрес устройства — Firebase Installation ID (FID): его выдаёт `onRegistered` в [RyadomMessagingService]
 * после [register]. Устаревший с 2026 года токен регистрации FCM не используется.
 * Автоматическая регистрация выключена (манифест, `firebase_messaging_auto_init_enabled`): FCM включается,
 * только когда вошёл волонтёр. Настройки проекта Firebase — в сборке (`ryadom.firebase.*`), без google-services.json.
 */
class FcmPush(
    context: Context,
) {
    private val appContext = context.applicationContext

    val availability: PushAvailability =
        when {
            BuildConfig.FIREBASE_APP_ID.isEmpty() -> PushAvailability.NOT_CONFIGURED

            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                appContext,
            ) != ConnectionResult.SUCCESS -> PushAvailability.NO_GOOGLE_SERVICES

            else -> PushAvailability.AVAILABLE
        }

    private val ready: Boolean by lazy {
        if (availability != PushAvailability.AVAILABLE) return@lazy false
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                val options =
                    FirebaseOptions
                        .Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build()
                FirebaseApp.initializeApp(appContext, options)
            }
            true
        } catch (e: IllegalArgumentException) {
            // Неверные настройки проекта в сборке.
            Log.w(TAG, "Firebase is misconfigured: ${e::class.java.simpleName}")
            false
        }
    }

    /** Зарегистрироваться в FCM: FID придёт в [RyadomMessagingService.onRegistered]. Повторный вызов безопасен. */
    fun register() {
        if (!ready) return
        FirebaseMessaging.getInstance().register().addOnFailureListener {
            // Нет связи с Google — FCM повторит сам при следующем запуске, а пока вызовы приходят через WebSocket.
            Log.w(TAG, "FCM registration failed: ${it::class.java.simpleName}")
        }
    }

    /**
     * Волонтёр вышел или сеанс закончился: отписать телефон от FCM. Тогда push-сервис отвечает серверу,
     * что устройства нет, и сервер удаляет его, даже если сам запрос на удаление (`DELETE /devices`) не дошёл.
     */
    fun unregister() {
        if (!ready) return
        FirebaseMessaging.getInstance().unregister()
    }

    private companion object {
        const val TAG = "FcmPush"
    }
}

/**
 * Сообщения FCM: вызов пришёл (`request.incoming`) или больше не ждёт ответа (`request.closed`).
 * Только данные, без текста (docs/api/openapi.yaml, `PushMessage`); неизвестный вид пропускается.
 * Вызывается в фоновом потоке, в том числе когда процесс приложения запущен ради этого сообщения.
 *
 * `onNewToken` не нужен: он сообщает устаревший токен регистрации, а сервер получает FID из [onRegistered].
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class RyadomMessagingService : FirebaseMessagingService() {
    private val container get() = (application as RyadomApplication).container

    override fun onRegistered(installationId: String) {
        container.onPushToken(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushMessage.fromData(message.data)?.let(container::onPush)
    }
}
