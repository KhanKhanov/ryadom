package ru.ryadom.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Канал push-уведомлений (схема `PushProvider` в `docs/api/openapi.yaml`). */
@Serializable
enum class PushProvider {
    /** Firebase Cloud Messaging — Android с сервисами Google. */
    @SerialName("fcm")
    FCM,

    /** RuStore Push — Android. */
    @SerialName("rustore")
    RUSTORE,

    /** Web Push — браузер. */
    @SerialName("webpush")
    WEB_PUSH,
}

/** Ключи подписки Web Push (`PushSubscription.getKey()` в base64url без `=`). */
@Serializable
data class WebPushKeys(
    /** Открытый ключ браузера P-256, 65 байт. */
    val p256dh: String,
    /** Секрет аутентификации, 16 байт. */
    val auth: String,
) {
    // Ключи подписки позволяют расшифровать уведомления — в логи они не попадают.
    override fun toString(): String = "WebPushKeys(***)"
}

/** Тело `POST /devices`: включить push-уведомления на устройстве. */
@Serializable
data class RegisterDeviceRequest(
    val provider: PushProvider,
    /** Токен устройства от FCM или RuStore; для Web Push — адрес подписки (`PushSubscription.endpoint`). */
    val token: String,
    /** Ключи подписки — только для [PushProvider.WEB_PUSH]. */
    val webPush: WebPushKeys? = null,
) {
    // По токену push-сервис доставит уведомление на устройство — в логи он не попадает.
    override fun toString(): String = "RegisterDeviceRequest(provider=$provider)"
}

/** Ответ `POST /devices`. [id] нужен, чтобы выключить уведомления (`DELETE /devices/{deviceId}`). */
@Serializable
data class Device(
    val id: String,
)

/** Ответ `GET /push/config`. */
@Serializable
data class PushConfig(
    /** Открытый ключ VAPID для `PushManager.subscribe()`; `null` — Web Push на сервере не настроен. */
    val webPushPublicKey: String?,
)

/** Вид push-уведомления; [value] — значение поля `type`. */
enum class PushMessageType(
    val value: String,
) {
    /** Новый вызов: показать уведомление, на Android — позвонить. */
    REQUEST_INCOMING("request.incoming"),

    /** Вызов больше не ждёт ответа (принят, отменён, никто не ответил): убрать уведомление. */
    REQUEST_CLOSED("request.closed"),
}

/**
 * Содержимое push-уведомления (схема `PushMessage`). Уведомление идёт через внешние сервисы
 * (Google, VK, компании-разработчики браузеров), поэтому в нём только вид события и id запроса,
 * без данных о людях: остальное клиент узнаёт через API.
 */
data class PushMessage(
    val type: PushMessageType,
    val requestId: String,
) {
    /** Поле `data` в FCM и RuStore (там все значения — строки); тот же объект — JSON в теле Web Push. */
    fun toData(): Map<String, String> = mapOf(KEY_TYPE to type.value, KEY_REQUEST_ID to requestId)

    companion object {
        const val KEY_TYPE = "type"
        const val KEY_REQUEST_ID = "requestId"

        /**
         * Разбирает поле `data` уведомления. `null` — неизвестный вид (его могла прислать новая версия сервера)
         * или нет id запроса: такое уведомление клиент пропускает.
         */
        fun fromData(data: Map<String, String>): PushMessage? {
            val type = PushMessageType.entries.firstOrNull { it.value == data[KEY_TYPE] } ?: return null
            val requestId = data[KEY_REQUEST_ID]?.takeIf { it.isNotBlank() } ?: return null
            return PushMessage(type, requestId)
        }
    }
}
