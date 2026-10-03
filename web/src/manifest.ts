// Манифест веб-приложения: название и иконка, когда сайт добавлен на экран «Домой».
// На iPhone и iPad без этого не работают push-уведомления (docs/ARCHITECTURE.md, раздел 8).
// Файлы /manifest-ru.webmanifest и /manifest-en.webmanifest собирает vite.pwa.ts, ссылку на нужный — main.tsx.

import { strings, type Language } from './i18n.ts'

export const MANIFEST_LANGUAGES = Object.keys(strings) as Language[]

export function manifestPath(language: Language): string {
  return `/manifest-${language}.webmanifest`
}

export function webManifest(language: Language): string {
  const t = strings[language]
  const icons = [
    { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
    { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
    // Важное на иконке — в центре, поэтому Android может обрезать её по своей форме.
    { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
  ]
  return JSON.stringify(
    {
      name: t.appName,
      short_name: t.appName,
      description: t.manifestDescription,
      lang: language,
      start_url: '/',
      scope: '/',
      // Отдельное окно без адресной строки — так iOS разрешает сайту push-уведомления.
      display: 'standalone',
      background_color: '#ffffff',
      theme_color: '#1d4ed8',
      icons,
    },
    null,
    2,
  )
}
