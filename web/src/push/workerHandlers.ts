// Обработчики событий Service Worker: push и нажатие на уведомление. Сам Service Worker (serviceWorker.ts)
// только подключает их к событиям браузера, а здесь — логика, которую можно проверить тестами без браузера.

import type { Language } from '../i18n'
import { incomingNotification, NOTIFICATION_CLICK_MESSAGE, parsePushMessage, shownAt, STALE_NOTIFICATION_MS } from './pushMessage'

/** Уведомление, которое уже показано. */
export type ShownNotification = { tag: string; data: unknown; close(): void }

/** Открытая вкладка сайта. */
export type SiteWindow = { visible: boolean; focus(): Promise<unknown>; postMessage(message: unknown): void }

/** Что нужно обработчикам от Service Worker. */
export type WorkerContext = {
  language: Language
  now(): number
  showNotification(title: string, options: NotificationOptions): Promise<void>
  notifications(): Promise<ShownNotification[]>
  windows(): Promise<SiteWindow[]>
  openWindow(url: string): Promise<unknown>
}

/**
 * Пришло push-уведомление. На новый вызов уведомление показывается всегда — даже если сайт открыт:
 * Safari отзывает подписку, если на push ничего не показано (Firefox — после нескольких таких push).
 * Если сайт виден на экране, уведомление без звука: страница звонит сама.
 */
export async function handlePush(context: WorkerContext, payload: unknown): Promise<void> {
  const message = parsePushMessage(payload)
  if (!message) return
  const shown = await context.notifications()
  if (message.type === 'request.closed') {
    for (const notification of shown) if (notification.tag === message.requestId) notification.close()
    return
  }
  const now = context.now()
  // Уведомления о давно закрытых вызовах больше не нужны: поиск длится около минуты.
  for (const notification of shown) {
    const time = shownAt(notification.data)
    if (time !== null && now - time > STALE_NOTIFICATION_MS) notification.close()
  }
  const visible = (await context.windows()).some((tab) => tab.visible)
  const { title, options } = incomingNotification(message.requestId, context.language, { silent: visible, now })
  await context.showNotification(title, options)
}

/**
 * Нажали на уведомление: показать открытую вкладку сайта (и попросить её перечитать вызовы) или открыть новую.
 * Вызовы новая вкладка загрузит сама — после входа и подключения к серверу.
 */
export async function handleNotificationClick(context: WorkerContext, notification: ShownNotification): Promise<void> {
  notification.close()
  const windows = await context.windows()
  const target = windows.find((tab) => tab.visible) ?? windows[0]
  if (!target) {
    await context.openWindow('/')
    return
  }
  await target.focus()
  target.postMessage({ type: NOTIFICATION_CLICK_MESSAGE })
}
