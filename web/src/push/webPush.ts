// Web Push на странице: Service Worker, подписка браузера и уведомления о вызовах.
// Экраны работают с интерфейсом PushService; в тестах — поддельная реализация (src/testing).

import type { WebPushSubscription } from '../api/types'
import { NOTIFICATION_CLICK_MESSAGE } from './pushMessage'

/** Адрес Service Worker (собирается из serviceWorker.ts, см. vite.config.ts). */
const SERVICE_WORKER_URL = '/sw.js'

/**
 * - `supported` — уведомления можно включить;
 * - `needsHomeScreen` — iPhone или iPad: уведомления есть только у сайта, добавленного на экран «Домой»;
 * - `unsupported` — браузер не умеет Web Push.
 */
export type PushSupport = 'supported' | 'unsupported' | 'needsHomeScreen'

export type PushService = {
  support(): PushSupport
  /**
   * Brave: Web Push в нём работает через push-сервис Google, а он по умолчанию выключен
   * (brave://settings/privacy). Если подписаться не удалось, кабинет подскажет, что включить.
   */
  isBrave(): boolean
  /** Разрешение на уведомления: `default` — ещё не спрашивали. */
  permission(): NotificationPermission
  /**
   * Подключает Service Worker и возвращает подписку этого браузера, если она сделана с ключом сервера [publicKey].
   * Подписку со старым ключом (на сервере сменили ключи) удаляет: уведомления по ней не придут.
   */
  current(publicKey: string): Promise<WebPushSubscription | null>
  /**
   * Подписывается на уведомления. Вызывать прямо в обработчике нажатия, без запросов к серверу перед ним:
   * Safari и Firefox спрашивают разрешение только в ответ на действие пользователя.
   */
  subscribe(publicKey: string): Promise<WebPushSubscription>
  unsubscribe(): Promise<void>
  /** Убирает показанные уведомления о вызовах, для которых [shouldClose] вернула `true`. */
  closeNotifications(shouldClose: (requestId: string) => boolean): Promise<void>
  /** На уведомление нажали, и браузер показал эту вкладку. Возвращает функцию отписки. */
  onNotificationClick(listener: () => void): () => void
}

/** Настоящая реализация для браузера. */
export function createBrowserPush(): PushService {
  let registration: Promise<ServiceWorkerRegistration> | null = null

  function serviceWorker(): Promise<ServiceWorkerRegistration> {
    registration ??= navigator.serviceWorker
      .register(SERVICE_WORKER_URL)
      .then(() => navigator.serviceWorker.ready)
      .catch((error: unknown) => {
        // Следующая попытка — заново (например, сайт был недоступен).
        registration = null
        throw error
      })
    return registration
  }

  return {
    support() {
      const available = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
      if (available) return 'supported'
      // В Safari на iPhone и iPad Web Push есть только у сайтов, добавленных на экран «Домой».
      const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
      return ios ? 'needsHomeScreen' : 'unsupported'
    },

    isBrave() {
      // Brave не меняет userAgent, но добавляет navigator.brave.
      return 'brave' in navigator
    },

    permission() {
      return Notification.permission
    },

    async current(publicKey) {
      const subscription = await (await serviceWorker()).pushManager.getSubscription()
      if (!subscription) return null
      const key = subscription.options.applicationServerKey
      if (key && !sameBytes(new Uint8Array(key), fromBase64Url(publicKey))) {
        await subscription.unsubscribe()
        return null
      }
      return toData(subscription)
    },

    async subscribe(publicKey) {
      const ready = await serviceWorker()
      const subscription = await ready.pushManager.subscribe({
        // Каждое уведомление показывается — иначе браузеры не разрешают push.
        userVisibleOnly: true,
        applicationServerKey: fromBase64Url(publicKey),
      })
      return toData(subscription)
    },

    async unsubscribe() {
      if (!('serviceWorker' in navigator)) return
      const existing = await navigator.serviceWorker.getRegistration()
      const subscription = await existing?.pushManager.getSubscription()
      await subscription?.unsubscribe()
    },

    async closeNotifications(shouldClose) {
      if (!('serviceWorker' in navigator)) return
      const existing = await navigator.serviceWorker.getRegistration()
      for (const notification of (await existing?.getNotifications()) ?? []) {
        if (shouldClose(notification.tag)) notification.close()
      }
    },

    onNotificationClick(listener) {
      if (!('serviceWorker' in navigator)) return () => undefined
      const onMessage = (event: MessageEvent) => {
        const data: unknown = event.data
        if (typeof data === 'object' && data !== null && (data as { type?: unknown }).type === NOTIFICATION_CLICK_MESSAGE) listener()
      }
      navigator.serviceWorker.addEventListener('message', onMessage)
      return () => navigator.serviceWorker.removeEventListener('message', onMessage)
    },
  }
}

function toData(subscription: PushSubscription): WebPushSubscription {
  // toJSON() отдаёт ключи уже в base64url — в том виде, в каком их ждёт сервер.
  const json = subscription.toJSON()
  const p256dh = json.keys?.p256dh
  const auth = json.keys?.auth
  if (!p256dh || !auth) throw new Error('Push subscription has no keys')
  return { endpoint: subscription.endpoint, p256dh, auth }
}

/** base64url (ключ VAPID сервера) → байты для applicationServerKey. */
export function fromBase64Url(text: string): Uint8Array<ArrayBuffer> {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(text.length / 4) * 4, '=')
  const binary = atob(base64)
  const bytes = new Uint8Array(new ArrayBuffer(binary.length))
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index])
}
