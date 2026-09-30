package ru.ryadom.backend.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Часы, которые тест двигает сам: так проверяются сроки жизни токенов без ожидания. */
class TestClock(
    @Volatile var now: Instant = Instant.parse("2026-09-30T10:00:00Z"),
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
