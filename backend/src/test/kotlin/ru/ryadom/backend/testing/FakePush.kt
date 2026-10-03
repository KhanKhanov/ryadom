package ru.ryadom.backend.testing

import ru.ryadom.backend.push.DeviceRecord
import ru.ryadom.backend.push.PushResult
import ru.ryadom.backend.push.PushSender
import ru.ryadom.shared.api.AuthResponse
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushProvider
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Поддельные push-сервисы всех каналов: запоминают отправленные уведомления, без сети.
 * Тест может «удалить приложение» — добавить токен в [goneTokens], и push-сервис перестанет его знать.
 */
class FakePush(
    /** Каналы, «настроенные на сервере». */
    providers: Set<PushProvider> = PushProvider.entries.toSet(),
) {
    data class Sent(
        val device: DeviceRecord,
        val message: PushMessage,
        val ttl: Duration,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    val goneTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val senders: Map<PushProvider, PushSender> =
        providers.associateWith {
            object : PushSender {
                override suspend fun send(
                    device: DeviceRecord,
                    message: PushMessage,
                    ttl: Duration,
                ): PushResult {
                    if (device.token in goneTokens) return PushResult.DEVICE_GONE
                    sent += Sent(device, message, ttl)
                    return PushResult.SENT
                }
            }
        }

    /** Уведомления, отправленные на устройства пользователя. */
    fun sentTo(user: AuthResponse): List<Sent> = sent.filter { it.device.userId.toString() == user.user.id }
}
