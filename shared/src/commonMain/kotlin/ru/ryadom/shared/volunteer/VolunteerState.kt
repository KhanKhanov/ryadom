package ru.ryadom.shared.volunteer

import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.api.ServerEvent
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError

// Состояния приложения волонтёра: входящие вызовы, принятие, звонок, оценка (docs/ARCHITECTURE.md, раздел 7).
// Те же правила, что у кабинета волонтёра на сайте (web/src/volunteer/volunteerState.ts).
// Здесь только данные и чистые функции переходов — их легко проверить тестами.
// Сеть, WebSocket и звонок — в VolunteerController.

/** Сколько пропущенных вызовов помнить: дольше минуты вызов всё равно не ждёт. */
private const val MAX_SKIPPED = 50

/** Сообщение волонтёру: платформа показывает его и объявляет голосом. */
enum class VolunteerNotice {
    /** Пришёл вызов. */
    INCOMING,

    /** Вызов принял другой волонтёр. */
    TAKEN,

    /** Вы приняли этот вызов на другом устройстве или в браузере. */
    ACCEPTED_ELSEWHERE,

    /** Незрячий отменил вызов. */
    CANCELLED,

    /** Вызов больше не ждёт ответа: время поиска вышло. */
    NO_ANSWER,

    /** «Принять» не успело: вызов уже принял другой волонтёр. */
    TOO_LATE_TAKEN,

    /** «Принять» не успело: вызов уже закрыт. */
    TOO_LATE_CLOSED,

    /** «Принять» не вышло: у вас уже идёт звонок (принят на другом устройстве) — он сейчас откроется. */
    ALREADY_IN_CALL,

    /** Собеседник завершил звонок. */
    CALL_ENDED_BY_PEER,

    /** Звонок завершён (волонтёром, разговора не было). */
    CALL_ENDED,

    /** Звонок не состоялся: собеседник не подключился. */
    CALL_NOT_STARTED,

    /** Вызовы включены («Готов помогать»). */
    READY_ON,

    /** Вызовы выключены. */
    READY_OFF,

    /** Оценка отправлена. */
    RATED,
}

/** Сообщение; [id] растёт, чтобы одинаковое сообщение подряд тоже прозвучало. */
data class Notice(
    val id: Int,
    val kind: VolunteerNotice,
)

/**
 * Идущий звонок.
 * @property peerJoined собеседник хоть раз был в звонке — значит, разговор состоялся и его можно оценить.
 * @property ending волонтёр завершает звонок, ждём подтверждения сервера (звук уже выключен).
 */
data class VolunteerCall(
    val requestId: String,
    val credentials: CallCredentials,
    val call: CallState = CallState(),
    val peerJoined: Boolean = false,
    val ending: Boolean = false,
)

/** Звонок только что закончился — «Удалось помочь?». [endedByPeer] — завершил собеседник. */
data class FinishedCall(
    val requestId: String,
    val endedByPeer: Boolean,
)

/** Что показать волонтёру. */
enum class VolunteerScreen {
    /** Главный экран: «Готов помогать», входящие вызовы. */
    HOME,

    /** Звонок. */
    CALL,

    /** После звонка: «Удалось помочь?». */
    RATING,
}

data class VolunteerState(
    /** Вызовы, которые можно принять, — в порядке поступления. */
    val incoming: List<HelpRequest> = emptyList(),
    /** Пропущенные на этом устройстве: сервер о «Пропустить» не знает и вернёт их в списке ожидающих. */
    val skipped: List<String> = emptyList(),
    /** Какой вызов сейчас принимается (запрос отправлен, ответа ещё нет). */
    val accepting: String? = null,
    val call: VolunteerCall? = null,
    val finished: FinishedCall? = null,
    val notice: Notice? = null,
    /** Ждём ответа сервера на «Готов помогать» или оценку — эти кнопки неактивны. */
    val busy: Boolean = false,
    /** Ошибка последнего действия; пропадает при следующем действии. */
    val error: UserError? = null,
    /** Связь с сервером событий: без неё вызовы приходят только push-уведомлением. */
    val connection: RealtimeStatus = RealtimeStatus.CONNECTING,
    /**
     * Звонок и ожидающие вызовы хотя бы раз сверены с сервером после запуска. До этого пустой список
     * ещё ничего не значит: например, приложение открыли из уведомления о вызове, а список ещё грузится.
     */
    val synced: Boolean = false,
) {
    val screen: VolunteerScreen
        get() =
            when {
                call != null -> VolunteerScreen.CALL
                finished != null -> VolunteerScreen.RATING
                else -> VolunteerScreen.HOME
            }

    /** Вызов звонит: есть кого принять, а звонка нет. */
    val ringing: Boolean get() = incoming.isNotEmpty() && call == null
}

/** Почему не удалось принять вызов. */
enum class AcceptFailure {
    /** Вызов уже принял другой волонтёр. */
    TAKEN,

    /** Вызов закрыт (отменён, время вышло) или его нет. */
    CLOSED,

    /** У волонтёра уже идёт звонок. */
    ALREADY_IN_CALL,

    /** Нет связи или другая ошибка — вызов остаётся, можно попробовать ещё раз. */
    RETRY,
}

/** Событие WebSocket о запросе. */
fun VolunteerState.withEvent(event: ServerEvent.RequestEvent): VolunteerState {
    val request = event.request
    if (event is ServerEvent.RequestIncoming) {
        val known = incoming.any { it.id == request.id } || call?.requestId == request.id || request.id in skipped
        if (known || request.status != RequestStatus.SEARCHING) return this
        return copy(incoming = incoming + request).notify(VolunteerNotice.INCOMING)
    }
    val notice =
        when (event) {
            is ServerEvent.RequestTaken -> VolunteerNotice.TAKEN

            // Волонтёру request.accepted приходит, когда он сам принял вызов на другом устройстве или в браузере.
            is ServerEvent.RequestAccepted -> VolunteerNotice.ACCEPTED_ELSEWHERE

            is ServerEvent.RequestCancelled -> VolunteerNotice.CANCELLED

            is ServerEvent.RequestNoAnswer -> VolunteerNotice.NO_ANSWER

            else -> null
        }
    return withRequest(request, notice)
}

/**
 * Свежее состояние запроса (из события или REST API): убрать его из входящих, если его больше нельзя
 * принять, и закончить звонок, если он закрыт.
 */
fun VolunteerState.withRequest(
    request: HelpRequest,
    notice: VolunteerNotice? = null,
): VolunteerState {
    if (call?.requestId == request.id) return if (request.status.isCallActive) this else closeCall()
    if (request.status == RequestStatus.SEARCHING || incoming.none { it.id == request.id }) return this
    val updated = copy(incoming = incoming.without(request.id))
    // Пока волонтёр нажимает «Принять», о том, что вызов закрыт, скажет ответ сервера.
    return if (notice != null && accepting != request.id) updated.notify(notice) else updated
}

/**
 * Список ожидающих вызовов с сервера (`GET /requests/incoming`) заменяет показанный: вызовы, пришедшие
 * без связи (или открытые из уведомления), появляются и звонят, закрытые без связи — исчезают.
 * Пропущенные на этом устройстве не возвращаются.
 */
fun VolunteerState.withIncomingSynced(requests: List<HelpRequest>): VolunteerState {
    // Во время звонка другие вызовы не принять, а к его концу они уже закроются.
    if (call != null) return this
    val waiting = requests.filter { it.status == RequestStatus.SEARCHING && it.id !in skipped }
    // Вызов, который волонтёр как раз принимает, остаётся: ответ на «Принять» решит его судьбу.
    val inProgress = incoming.firstOrNull { it.id == accepting && waiting.none { w -> w.id == it.id } }
    val next = if (inProgress != null) waiting + inProgress else waiting
    val isNew = next.any { request -> incoming.none { it.id == request.id } }
    val updated = copy(incoming = next)
    return if (isNew) updated.notify(VolunteerNotice.INCOMING) else updated
}

/** Звонок, найденный через `GET /requests/current` (после перезапуска приложения). */
fun VolunteerState.withCallRestored(request: HelpRequest): VolunteerState {
    val credentials = request.call
    if (call?.requestId == request.id || credentials == null || !request.status.isCallActive) return this
    return startCall(request.id, credentials)
}

/** «Пропустить»: вызов исчезает только на этом устройстве. */
fun VolunteerState.skip(requestId: String): VolunteerState =
    copy(incoming = incoming.without(requestId), skipped = (skipped + requestId).takeLast(MAX_SKIPPED))

fun VolunteerState.acceptStarted(requestId: String): VolunteerState = copy(accepting = requestId, error = null)

/** Сервер ответил на «Принять»: [request] — с данными для звонка. */
fun VolunteerState.acceptSucceeded(request: HelpRequest): VolunteerState {
    val credentials = request.call
    if (credentials == null || !request.status.isCallActive) return acceptFailed(request.id, AcceptFailure.CLOSED)
    return startCall(request.id, credentials)
}

/** Принять не удалось. [error] — что сказать при [AcceptFailure.RETRY]. */
fun VolunteerState.acceptFailed(
    requestId: String,
    reason: AcceptFailure,
    error: UserError? = null,
): VolunteerState {
    val stillAccepting = if (accepting == requestId) null else accepting
    return when (reason) {
        AcceptFailure.RETRY -> {
            copy(accepting = stillAccepting, error = error)
        }

        AcceptFailure.ALREADY_IN_CALL -> {
            copy(accepting = stillAccepting).notify(VolunteerNotice.ALREADY_IN_CALL)
        }

        AcceptFailure.TAKEN, AcceptFailure.CLOSED -> {
            val notice = if (reason == AcceptFailure.TAKEN) VolunteerNotice.TOO_LATE_TAKEN else VolunteerNotice.TOO_LATE_CLOSED
            copy(accepting = stillAccepting, incoming = incoming.without(requestId)).notify(notice)
        }
    }
}

/** Новое состояние звонка от платформы. */
fun VolunteerState.withCallState(
    requestId: String,
    state: CallState,
): VolunteerState {
    val current = call ?: return this
    if (current.requestId != requestId) return this
    return copy(call = current.copy(call = state, peerJoined = current.peerJoined || state.peer == PeerPresence.PRESENT))
}

/** Волонтёр нажал «Завершить звонок». */
fun VolunteerState.endStarted(requestId: String): VolunteerState {
    val current = call ?: return this
    if (current.requestId != requestId) return this
    return copy(call = current.copy(ending = true), error = null)
}

/**
 * Волонтёр завершил звонок [requestId]: сервер подтвердил (или отказал так, что повтор не поможет).
 * Событие `request.ended` могло прийти раньше — тогда звонок уже закрыт.
 */
fun VolunteerState.callEnded(requestId: String): VolunteerState {
    val current = call ?: return this
    if (current.requestId != requestId) return this
    return copy(call = current.copy(ending = true)).closeCall()
}

/** Ответ на «Удалось помочь?» отправлен ([rated]) или пропущен. */
fun VolunteerState.ratingDone(rated: Boolean): VolunteerState {
    val done = copy(finished = null, busy = false, error = null)
    return if (rated) done.notify(VolunteerNotice.RATED) else done
}

private fun VolunteerState.startCall(
    requestId: String,
    credentials: CallCredentials,
): VolunteerState =
    // Во время звонка другие вызовы не принять, а к его концу они уже закроются: поиск длится около минуты.
    copy(call = VolunteerCall(requestId, credentials), accepting = null, incoming = emptyList(), finished = null, error = null)

/**
 * Звонок закрыт — волонтёром или собеседником. «Удалось помочь?» спрашиваем, только если разговор был:
 * если незрячий отменил вызов или не подключился, оценивать нечего (как у незрячего, раздел 7).
 */
private fun VolunteerState.closeCall(): VolunteerState {
    val current = call ?: return this
    val byPeer = !current.ending
    val closed = copy(call = null)
    if (!current.peerJoined) return closed.notify(if (byPeer) VolunteerNotice.CALL_NOT_STARTED else VolunteerNotice.CALL_ENDED)
    val finished = closed.copy(finished = FinishedCall(current.requestId, byPeer))
    return if (byPeer) finished.notify(VolunteerNotice.CALL_ENDED_BY_PEER) else finished
}

internal fun VolunteerState.notify(kind: VolunteerNotice): VolunteerState = copy(notice = Notice((notice?.id ?: 0) + 1, kind))

private fun List<HelpRequest>.without(requestId: String) = filter { it.id != requestId }

/** Звонок принят и ещё не закончен. */
private val RequestStatus.isCallActive: Boolean get() = this == RequestStatus.ACCEPTED || this == RequestStatus.IN_CALL
