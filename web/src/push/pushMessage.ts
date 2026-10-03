// Содержимое push-уведомления (схема PushMessage в docs/api/openapi.yaml) и уведомление о вызове.
// Файл собирается и в Service Worker (serviceWorker.ts), поэтому здесь нет DOM и React.

import { strings, type Language } from '../i18n'
import { isObject } from '../api/types'

/**
 * - `request.incoming` — новый вызов;
 * - `request.closed` — вызов больше не ждёт ответа (браузерам сервер его не шлёт, но разобрать его можно).
 */
export type PushMessage = { type: 'request.incoming' | 'request.closed'; requestId: string }

/** Сообщение из тела push; `null` — неизвестный вид (из новой версии сервера) или повреждённое. */
export function parsePushMessage(value: unknown): PushMessage | null {
  if (!isObject(value) || typeof value.requestId !== 'string' || value.requestId === '') return null
  if (value.type !== 'request.incoming' && value.type !== 'request.closed') return null
  return { type: value.type, requestId: value.requestId }
}

/**
 * Через сколько уведомление о вызове точно устарело: поиск волонтёра длится около минуты.
 * Такие уведомления Service Worker убирает, когда приходит новый вызов.
 */
export const STALE_NOTIFICATION_MS = 2 * 60_000

/** Данные, которые хранятся в самом уведомлении. */
export type CallNotificationData = { requestId: string; shownAt: number }

/** Время показа уведомления о вызове; `null` — уведомление не наше. */
export function shownAt(data: unknown): number | null {
  return isObject(data) && typeof data.shownAt === 'number' ? data.shownAt : null
}

/**
 * Уведомление о новом вызове. [silent] — без звука: страница сайта открыта на экране и звонит сама.
 * tag — id запроса: по нему уведомление закрывается, когда вызов уже не ждёт ответа.
 */
export function incomingNotification(
  requestId: string,
  language: Language,
  options: { silent: boolean; now: number },
): { title: string; options: NotificationOptions } {
  const t = strings[language]
  const data: CallNotificationData = { requestId, shownAt: options.now }
  return {
    title: t.pushNotificationTitle,
    options: {
      body: t.pushNotificationBody,
      tag: requestId,
      lang: language,
      icon: '/icon-192.png',
      // Вызов не исчезает сам, пока человек не ответит или не закроет его, — как входящий звонок.
      requireInteraction: true,
      silent: options.silent,
      data,
    },
  }
}

/** Сообщение, которое Service Worker отправляет странице, когда на уведомление нажали. */
export const NOTIFICATION_CLICK_MESSAGE = 'ryadom.notification-click'
