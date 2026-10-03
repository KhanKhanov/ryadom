package ru.ryadom.backend.push

import ru.ryadom.shared.api.PushMessage
import java.time.Duration

/**
 * Отправитель push-уведомлений одного канала: FCM, RuStore или Web Push (в тестах — поддельный).
 * Вся логика «кому и когда» — выше, в [PushNotifier] и подборе волонтёров; новый канал (APNs для iOS)
 * добавляется новой реализацией этого интерфейса (CLAUDE.md, правило 7).
 */
interface PushSender {
    /**
     * Отправляет [message] на [device]. [ttl] — сколько push-сервис хранит уведомление, если устройство
     * сейчас не в сети: вызов нужен, только пока идёт поиск. Ответ push-сервиса — в результате, а не исключением.
     */
    suspend fun send(
        device: DeviceRecord,
        message: PushMessage,
        ttl: Duration,
    ): PushResult
}

enum class PushResult {
    /** Push-сервис принял уведомление. */
    SENT,

    /** Push-сервис не знает устройство (приложение удалено, подписка отменена) — запись можно удалить. */
    DEVICE_GONE,

    /** Не получилось сейчас: нет сети, сбой или лимит push-сервиса. Устройство остаётся. */
    FAILED,
}
