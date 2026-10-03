package ru.ryadom.backend.requests

import ru.ryadom.backend.push.PushNotifier
import ru.ryadom.shared.api.PushMessage
import ru.ryadom.shared.api.PushMessageType
import ru.ryadom.shared.api.PushProvider
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

/**
 * Push-уведомления волонтёрам о запросе: новый вызов и «вызов больше не ждёт ответа».
 * Уведомление нужно, только пока идёт поиск, поэтому push-сервис хранит его не дольше, чем осталось искать:
 * телефон, вернувшийся в сеть через пять минут, не зазвонит о давно закрытом вызове.
 */
class RequestPushes(
    private val push: PushNotifier,
    private val searchTimeout: Duration,
    private val clock: Clock,
) {
    /** Каналы push, через которые можно дозвониться до волонтёра без открытого WebSocket. */
    val providers get() = push.providers

    fun incoming(
        volunteers: Collection<Uuid>,
        request: HelpRequestRecord,
    ) = send(volunteers, request, PushMessageType.REQUEST_INCOMING, PushProvider.entries.toSet())

    /**
     * Вызов закрыт (принят, отменён, никто не ответил): на Android звонок пора остановить.
     * Браузерам это уведомление не отправляется: на каждое Web Push сайт обязан показать уведомление
     * (Safari после трёх «молчаливых» отзывает подписку, Firefox — после шестнадцати), а показывать
     * «вызов закрыт» незачем — открытый сайт сам уберёт вызов, получив событие WebSocket.
     */
    fun closed(
        volunteers: Collection<Uuid>,
        request: HelpRequestRecord,
    ) = send(volunteers, request, PushMessageType.REQUEST_CLOSED, PushProvider.entries.toSet() - PushProvider.WEB_PUSH)

    private fun send(
        volunteers: Collection<Uuid>,
        request: HelpRequestRecord,
        type: PushMessageType,
        channels: Set<PushProvider>,
    ) {
        val searchLeft = Duration.between(clock.instant(), request.createdAt.plus(searchTimeout))
        push.send(volunteers, PushMessage(type, request.id.toString()), ttl = maxOf(searchLeft, MIN_TTL), only = channels)
    }

    private companion object {
        /** Даже в конце поиска уведомлению нужно время дойти до устройства, которое в сети. */
        val MIN_TTL: Duration = Duration.ofSeconds(10)
    }
}
