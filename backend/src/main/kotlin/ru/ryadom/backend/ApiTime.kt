package ru.ryadom.backend

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Время в ответах API: ISO 8601 в UTC с точностью до секунды, например `2026-09-30T10:00:00Z`. */
internal fun Instant.toApiTime(): String = DateTimeFormatter.ISO_INSTANT.format(truncatedTo(ChronoUnit.SECONDS))
