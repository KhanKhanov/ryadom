package ru.ryadom.shared.help

import ru.ryadom.shared.api.CallCredentials
import ru.ryadom.shared.api.HelpRequest
import ru.ryadom.shared.api.RequestStatus
import ru.ryadom.shared.call.CallState
import ru.ryadom.shared.call.PeerPresence
import ru.ryadom.shared.client.RealtimeStatus
import ru.ryadom.shared.client.UserError

// Состояния приложения незрячего: «кнопка → поиск → звонок → оценка» (docs/ARCHITECTURE.md, разделы 3 и 7).
// Здесь только данные и чистые функции переходов — их легко проверить тестами.
// Сеть, WebSocket и звонок — в BlindHelpController.

/** Чем закончился прошлый запрос — об этом говорит (и объявляет голосом) главный экран. */
enum class HelpOutcome {
    /** Никто не принял запрос. */
    NO_ANSWER,

    /**
     * Никто не принял запрос ночью. Ночью почти все волонтёры в режиме «не беспокоить»,
     * поэтому текст другой: ответа может не быть дольше, чем днём.
     */
    NO_ANSWER_AT_NIGHT,

    /** Поиск отменён. */
    CANCELLED,

    /** Волонтёр принял запрос, но завершил звонок до начала разговора: можно попросить помощи снова. */
    VOLUNTEER_LEFT,

    /** Незрячий сам завершил звонок до начала разговора. */
    CALL_ENDED,

    /** Оценка отправлена. */
    RATED,
}

/** Что сейчас на экране незрячего. */
sealed interface BlindScreen {
    /** Проверяем, нет ли незавершённого запроса (после перезапуска приложения). */
    data object Loading : BlindScreen

    /** Главный экран: большая кнопка «Позвать волонтёра». [outcome] — чем закончился прошлый запрос. */
    data class Ready(
        val outcome: HelpOutcome? = null,
    ) : BlindScreen

    /** Ищем волонтёра; можно отменить. */
    data class Searching(
        val requestId: String,
    ) : BlindScreen

    /**
     * Волонтёр принял запрос: звонок.
     * @property volunteerJoined волонтёр хоть раз был в звонке — значит, разговор состоялся и его можно оценить.
     * @property ending незрячий завершает звонок, ждём подтверждения сервера (камера и микрофон уже выключены).
     */
    data class Call(
        val requestId: String,
        val credentials: CallCredentials,
        val call: CallState = CallState(),
        val volunteerJoined: Boolean = false,
        val ending: Boolean = false,
    ) : BlindScreen

    /** Звонок закончился: «Удалось получить помощь?». */
    data class Rating(
        val requestId: String,
    ) : BlindScreen
}

/** id запроса, которым сейчас занят экран; `null` — запроса нет. */
val BlindScreen.requestId: String?
    get() =
        when (this) {
            is BlindScreen.Searching -> requestId
            is BlindScreen.Call -> requestId
            is BlindScreen.Rating -> requestId
            BlindScreen.Loading, is BlindScreen.Ready -> null
        }

data class BlindState(
    val screen: BlindScreen = BlindScreen.Loading,
    /** Ждём ответа сервера на действие пользователя — кнопки неактивны. */
    val busy: Boolean = false,
    /** Ошибка последнего действия; пропадает при следующем действии. */
    val error: UserError? = null,
    /** Связь с сервером событий. Без неё не узнать, что волонтёр найден. */
    val connection: RealtimeStatus = RealtimeStatus.CONNECTING,
    /**
     * Последний запрос, который у нас уже закрыт. Опоздавшие события о нём пропускаются: например,
     * `request.accepted`, отправленный за мгновение до отмены, не должен снова открыть звонок.
     */
    val closedRequestId: String? = null,
)

/**
 * Новое состояние по свежим данным о запросе [request] — из события WebSocket или ответа сервера.
 * @param isNight сейчас ночь по местному времени (для текста «никто не ответил»).
 * @param byUser незрячий сам закрыл запрос (ответ на его отмену или завершение звонка).
 */
fun BlindState.withRequest(
    request: HelpRequest,
    isNight: Boolean,
    byUser: Boolean = false,
): BlindState {
    if (request.id == closedRequestId) return this
    val screen = screen
    val trackedId = screen.requestId
    // У незрячего один активный запрос; данные о другом — устаревшие.
    if (trackedId != null && trackedId != request.id) return this
    if (screen is BlindScreen.Rating) return this
    return when (request.status) {
        RequestStatus.SEARCHING -> {
            // Ответ на создание запроса может прийти позже события о том, что волонтёр уже найден.
            if (screen is BlindScreen.Call) this else copy(screen = BlindScreen.Searching(request.id))
        }

        RequestStatus.ACCEPTED, RequestStatus.IN_CALL -> {
            val credentials = request.call
            // В идущем звонке данные для входа не меняем — иначе звонок переподключится.
            if (screen is BlindScreen.Call || credentials == null) this else copy(screen = BlindScreen.Call(request.id, credentials))
        }

        RequestStatus.NO_ANSWER -> {
            if (trackedId == null) {
                this
            } else {
                closed(request.id, BlindScreen.Ready(if (isNight) HelpOutcome.NO_ANSWER_AT_NIGHT else HelpOutcome.NO_ANSWER))
            }
        }

        RequestStatus.CANCELLED, RequestStatus.ENDED -> {
            if (trackedId == null) this else closed(request.id, afterClose(screen, request.status, byUser))
        }
    }
}

/** Новое состояние звонка от платформы. */
fun BlindState.withCallState(
    requestId: String,
    call: CallState,
): BlindState {
    val screen = screen as? BlindScreen.Call ?: return this
    if (screen.requestId != requestId) return this
    return copy(screen = screen.copy(call = call, volunteerJoined = screen.volunteerJoined || call.peer == PeerPresence.PRESENT))
}

/**
 * Куда перейти, когда запрос закрыт. Оценка — только если разговор был: если волонтёр завершил звонок
 * раньше, чем подключился, спрашивать «Удалось получить помощь?» незачем — лучше предложить попросить снова.
 */
private fun afterClose(
    screen: BlindScreen,
    status: RequestStatus,
    byUser: Boolean,
): BlindScreen {
    if (screen !is BlindScreen.Call) return BlindScreen.Ready(HelpOutcome.CANCELLED)
    if (screen.volunteerJoined) return BlindScreen.Rating(screen.requestId)
    val outcome =
        when {
            byUser || screen.ending -> HelpOutcome.CALL_ENDED
            status == RequestStatus.ENDED -> HelpOutcome.VOLUNTEER_LEFT
            else -> HelpOutcome.CANCELLED
        }
    return BlindScreen.Ready(outcome)
}

private fun BlindState.closed(
    requestId: String,
    next: BlindScreen,
): BlindState = copy(screen = next, closedRequestId = requestId)

/**
 * Ночь — когда почти все волонтёры в режиме «не беспокоить». Совпадает с окном «не беспокоить»
 * по умолчанию на сервере (`profile.defaultDoNotDisturb` в `backend/src/main/resources/application.conf`):
 * время волонтёров и незрячего почти всегда в одних и тех же часовых поясах.
 */
fun isNightHour(hour: Int): Boolean = hour >= NIGHT_FROM_HOUR || hour < NIGHT_TO_HOUR

private const val NIGHT_FROM_HOUR = 22
private const val NIGHT_TO_HOUR = 8
