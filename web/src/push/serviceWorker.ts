/// <reference lib="webworker" />
// Service Worker сайта: показывает уведомления о вызовах, когда сайт закрыт (Web Push).
// Собирается отдельно от сайта в один файл /sw.js (vite.config.ts), типы проверяет tsconfig.sw.json.
// Страницы сайта он не кэширует и запросы не перехватывает.

import { detectLanguage } from '../i18n'
import { handleNotificationClick, handlePush, type WorkerContext } from './workerHandlers'

declare const self: ServiceWorkerGlobalScope

const context: WorkerContext = {
  // Язык уведомления — по настройкам браузера, как и у страниц сайта.
  language: detectLanguage(self.navigator.languages),
  now: () => Date.now(),
  showNotification: (title, options) => self.registration.showNotification(title, options),
  notifications: async () =>
    (await self.registration.getNotifications()).map((notification) => ({
      tag: notification.tag,
      data: notification.data,
      close: () => notification.close(),
    })),
  windows: async () =>
    (await self.clients.matchAll({ type: 'window', includeUncontrolled: true })).map((client) => ({
      visible: client.visibilityState === 'visible',
      focus: () => client.focus(),
      postMessage: (message: unknown) => client.postMessage(message),
    })),
  openWindow: (url) => self.clients.openWindow(url),
}

// Новая версия Service Worker начинает работать сразу: кэша у него нет, ждать закрытия вкладок незачем.
self.addEventListener('install', () => void self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('push', (event) => {
  let payload: unknown = null
  try {
    payload = event.data?.json()
  } catch {
    // Не JSON — такое уведомление не от нашего сервера.
  }
  event.waitUntil(handlePush(context, payload))
})

self.addEventListener('notificationclick', (event) => {
  const notification = event.notification
  event.waitUntil(
    handleNotificationClick(context, { tag: notification.tag, data: notification.data, close: () => notification.close() }),
  )
})
