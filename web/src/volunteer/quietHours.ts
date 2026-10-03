// Время тишины (окно «не беспокоить»): в эти часы по местному времени волонтёру не приходят вызовы.

import type { DoNotDisturb } from '../api/types'

/** Есть ли время тишины: если начало равно концу, окно пустое (docs/api/openapi.yaml, DoNotDisturb). */
export function hasQuietHours(dnd: DoNotDisturb): boolean {
  return dnd.from !== dnd.to
}

/**
 * Время [time] внутри окна [from]–[to] — так же, как считает сервер (VolunteerMatcher.isInWindow):
 * начало включительно, конец — нет, окно может переходить через полночь. Время — строки `ЧЧ:ММ`,
 * их можно сравнивать как строки.
 */
export function isInWindow(time: string, from: string, to: string): boolean {
  if (from === to) return false
  if (from < to) return time >= from && time < to
  return time >= from || time < to
}

/** Сейчас время тишины по часовому поясу [timezone]. Неизвестный браузеру пояс — считаем, что нет. */
export function isQuietNow(dnd: DoNotDisturb, timezone: string, now: Date): boolean {
  const time = localTime(now, timezone)
  return time !== null && isInWindow(time, dnd.from, dnd.to)
}

/** Время `ЧЧ:ММ` в часовом поясе [timezone]; `null` — пояс неизвестен браузеру. */
export function localTime(now: Date, timezone: string): string | null {
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: timezone,
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(now)
    const hour = parts.find((part) => part.type === 'hour')?.value
    const minute = parts.find((part) => part.type === 'minute')?.value
    return hour && minute ? `${hour}:${minute}` : null
  } catch {
    return null
  }
}

/** Часовой пояс браузера (IANA), например `Europe/Moscow`; `null` — браузер его не сообщает. */
export function browserTimezone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null
  } catch {
    return null
  }
}
