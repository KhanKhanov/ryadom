package ru.ryadom.backend.requests

import org.slf4j.LoggerFactory
import ru.ryadom.backend.MatchingConfig
import ru.ryadom.backend.RequestsConfig
import ru.ryadom.backend.realtime.RealtimeHub
import ru.ryadom.backend.users.UserRepository
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Поиск волонтёра: волны уведомлений, закрытие поиска без ответа (`no_answer`)
 * и страховочное закрытие звонков, в которые никто не вошёл или о завершении которых LiveKit не сообщил.
 *
 * Ход поиска хранится в базе (`help_requests.next_wave`), поэтому после перезапуска сервера
 * поиск продолжается с того места, где остановился. [tick] вызывается раз в `matching.checkInterval`;
 * в тестах — вручную, вместе с поддельными часами.
 */
class RequestDispatcher(
    private val requests: HelpRequestRepository,
    private val users: UserRepository,
    private val matcher: VolunteerMatcher,
    private val hub: RealtimeHub,
    private val pushes: RequestPushes,
    private val locks: RequestLocks,
    private val config: MatchingConfig,
    private val limits: RequestsConfig,
    private val clock: Clock,
) {
    /** Первая волна — сразу после создания запроса, не дожидаясь [tick]. */
    suspend fun startSearch(request: HelpRequestRecord) = locks.withLock(request.id) { advance(request.id) }

    suspend fun tick() {
        for (request in requests.findSearching()) {
            locks.withLock(request.id) { advance(request.id) }
        }
        closeUnjoinedCalls()
        closeStaleCalls()
    }

    /** Отправляет волну, если подошло её время, или закрывает поиск по таймауту. */
    private suspend fun advance(requestId: Uuid) {
        // Перечитываем под блокировкой: запрос могли уже принять или отменить.
        val request = requests.findById(requestId)?.takeIf { it.status == RequestStatus.SEARCHING } ?: return
        val now = clock.instant()
        val elapsed = Duration.between(request.createdAt, now)
        if (elapsed >= config.searchTimeout) return closeWithoutAnswer(request, now)
        // Волна 0 — сразу, волна 1 — через waveInterval и т. д. Если сервер был выключен
        // и волны пропущены, отправляется только последняя из них.
        val dueWave = (elapsed.toMillis() / config.waveInterval.toMillis()).toInt()
        if (request.nextWave <= dueWave) sendWave(request, dueWave, now)
    }

    private suspend fun sendWave(
        request: HelpRequestRecord,
        wave: Int,
        now: Instant,
    ) {
        if (!requests.claimWave(request.id, expectedNextWave = request.nextWave, wave = wave)) return
        // Вызов можно доставить волонтёрам с открытым WebSocket и с устройствами в настроенных каналах push.
        // Автор запроса не получает собственный вызов, даже если успел сменить роль на волонтёра.
        val volunteers =
            users.findReachableVolunteers(connected = hub.connectedUsers(), pushProviders = pushes.providers, except = request.blindUserId)
        val activity = requests.volunteerActivity(request.id, volunteers.map { it.id })
        val candidates =
            volunteers.map {
                VolunteerCandidate(
                    user = it,
                    lastDisturbedAt = activity.lastDisturbedAt[it.id],
                    inCall = it.id in activity.inCall,
                    alreadyNotified = it.id in activity.notifiedForRequest,
                )
            }
        val size = if (wave == 0) config.firstWaveSize else config.nextWaveSize
        val chosen = matcher.select(candidates, request.language, request.genderPreference, now, size)
        requests.recordNotifications(request.id, wave, chosen, now)
        log.info("Request {} wave {}: {} volunteers notified", request.id, wave, chosen.size)
        hub.send(chosen, ServerEvent.RequestIncoming(request.toApi()))
        pushes.incoming(chosen, request)
    }

    private suspend fun closeWithoutAnswer(
        request: HelpRequestRecord,
        now: Instant,
    ) {
        val closed = requests.transition(request.id, from = setOf(RequestStatus.SEARCHING), to = RequestStatus.NO_ANSWER, now) ?: return
        log.info("Request {}: no answer", closed.id)
        val waiting = requests.findWaitingVolunteers(closed.id)
        hub.send(waiting + closed.blindUserId, ServerEvent.RequestNoAnswer(closed.toApi()))
        pushes.closed(waiting, closed)
    }

    /**
     * Запрос приняли, но в комнату звонка так никто и не вошёл (приложения упали, пропала сеть).
     * LiveKit не создаёт комнату без участников и о таком звонке не сообщит, а пока запрос активен,
     * волонтёр не получает вызовов, а незрячий не может попросить помощи снова.
     */
    private suspend fun closeUnjoinedCalls() {
        val now = clock.instant()
        for (call in requests.findUnjoinedCallsAcceptedBefore(now.minus(limits.joinTimeout))) {
            locks.withLock(call.id) {
                val ended = requests.transition(call.id, from = setOf(RequestStatus.ACCEPTED), to = RequestStatus.ENDED, now)
                if (ended != null) {
                    log.warn("Request {}: closed, nobody joined the call within {}", ended.id, limits.joinTimeout)
                    hub.sendCallEnded(ended)
                }
            }
        }
    }

    private suspend fun closeStaleCalls() {
        val now = clock.instant()
        for (call in requests.findCallsAcceptedBefore(now.minus(limits.maxCallDuration))) {
            locks.withLock(call.id) {
                val ended = requests.transition(call.id, from = CALL_STATUSES, to = RequestStatus.ENDED, now)
                if (ended != null) {
                    log.warn("Request {}: call closed after {} without room_finished from LiveKit", ended.id, limits.maxCallDuration)
                    hub.sendCallEnded(ended)
                }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RequestDispatcher::class.java)
    }
}
