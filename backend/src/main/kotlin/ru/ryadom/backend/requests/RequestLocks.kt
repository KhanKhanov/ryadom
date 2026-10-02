package ru.ryadom.backend.requests

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Очередь действий над одним запросом: смена статуса и рассылка событий о ней идут строго по одному.
 * Без неё волонтёр мог бы получить «новый запрос» уже после «запрос принят другим».
 *
 * Корректность статусов обеспечивает база (условные UPDATE), блокировка нужна только для порядка событий.
 * Блокировки не вкладываются друг в друга: внутри [withLock] нельзя брать блокировку другого запроса.
 */
class RequestLocks(
    stripes: Int = 64,
) {
    // Фиксированный набор блокировок вместо отдельной на каждый запрос: память не растёт,
    // а разные запросы почти не мешают друг другу.
    private val mutexes = List(stripes) { Mutex() }

    suspend fun <T> withLock(
        requestId: Uuid,
        block: suspend () -> T,
    ): T = mutexes[Math.floorMod(requestId.hashCode(), mutexes.size)].withLock { block() }
}
