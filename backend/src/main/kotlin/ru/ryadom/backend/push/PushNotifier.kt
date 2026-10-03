package ru.ryadom.backend.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.ryadom.backend.errors.withoutMessages
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushProvider
import java.time.Duration
import kotlin.uuid.Uuid

/**
 * Рассылает push-уведомления на все устройства пользователей через отправителей каналов ([PushSender]).
 *
 * Отправка идёт в фоне и не задерживает того, кто её начал: волна вызовов уходит, пока запрос
 * под блокировкой, а push-сервис может отвечать секундами. Устройства, которые push-сервис больше
 * не знает, удаляются. Ошибки только пишутся в лог: уведомление — дополнение к WebSocket, а не
 * единственный путь, и повторять вызов через минуту уже незачем.
 *
 * @param senders отправители по каналам; канала без отправителя (не настроен на сервере) как будто нет.
 */
class PushNotifier(
    private val devices: DeviceRepository,
    private val senders: Map<PushProvider, PushSender>,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope =
        CoroutineScope(
            job + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> log.error("Push delivery failed", e.withoutMessages()) },
        )

    /** Не больше стольких запросов к push-сервисам одновременно — большая волна не забьёт сеть сервера. */
    private val parallelSends = Semaphore(MAX_PARALLEL_SENDS)

    /** Каналы, через которые сервер может доставить уведомление. */
    val providers: Set<PushProvider> = senders.keys

    /** Отправляет [message] на устройства пользователей [userIds] в каналах [only]. Возвращается сразу. */
    fun send(
        userIds: Collection<Uuid>,
        message: PushMessage,
        ttl: Duration,
        only: Set<PushProvider> = providers,
    ) {
        val channels = only intersect providers
        if (userIds.isEmpty() || channels.isEmpty()) return
        scope.launch {
            for (device in devices.findByUsers(userIds.toSet(), channels)) {
                launch { deliver(device, message, ttl) }
            }
        }
    }

    private suspend fun deliver(
        device: DeviceRecord,
        message: PushMessage,
        ttl: Duration,
    ) {
        val sender = senders[device.provider] ?: return
        val result =
            parallelSends.withPermit {
                try {
                    sender.send(device, message, ttl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Push to device {} ({}) failed", device.id, device.provider, e.withoutMessages())
                    PushResult.FAILED
                }
            }
        if (result == PushResult.DEVICE_GONE) {
            devices.deleteGone(device.id)
            log.info("Device {} removed: unknown to {}", device.id, device.provider)
        }
    }

    /** Для тестов: дождаться, пока уйдут все начатые отправки. */
    suspend fun awaitIdle() {
        while (true) {
            val active = job.children.filter { it.isActive }.toList()
            if (active.isEmpty()) return
            active.joinAll()
        }
    }

    override fun close() = scope.cancel()

    private companion object {
        const val MAX_PARALLEL_SENDS = 16
        val log = LoggerFactory.getLogger(PushNotifier::class.java)
    }
}
