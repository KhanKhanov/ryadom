// Настройки веб-приложения, не зависящие от языка.

/**
 * Где лежит исходный код. Лицензия AGPL требует, чтобы пользователи сервиса могли его получить.
 * Если вы разворачиваете свою изменённую версию — укажите здесь свой репозиторий.
 */
export const SOURCE_CODE_URL = 'https://github.com/KhanKhanov/ryadom'

/**
 * Путь к API на том же адресе, что и сайт. В разработке Vite пересылает `/api/...` на backend
 * (`vite.config.ts`), на сервере это будет делать Caddy. Раз сайт и API на одном адресе,
 * браузеру не нужен CORS, а серверу — список разрешённых сайтов.
 */
export const API_BASE_PATH = '/api'

export type AppConfig = {
  /** Начало адресов REST API, например `/api`. */
  apiBaseUrl: string
  /** Адрес WebSocket событий, например `ws://localhost:5173/api/ws`. */
  realtimeUrl: string
  /** Адрес, на который Яндекс ID возвращает пользователя после входа (регистрируется на oauth.yandex.ru). */
  yandexRedirectUri: string
  /** client_id приложения в Яндекс ID; `null` — вход через Яндекс не настроен, кнопки нет. */
  yandexClientId: string | null
  /** Инструменты разработчика: вход без OAuth и страница «тестовый незрячий». В боевой сборке выключены. */
  devTools: boolean
}

/** Собирает настройки из адреса страницы и переменных сборки (`web/.env*`, см. README). */
export function loadConfig(location: Pick<Location, 'protocol' | 'host' | 'origin'>, env: ImportMetaEnv): AppConfig {
  const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return {
    apiBaseUrl: API_BASE_PATH,
    realtimeUrl: `${wsProtocol}//${location.host}${API_BASE_PATH}/ws`,
    yandexRedirectUri: `${location.origin}/`,
    yandexClientId: env.VITE_YANDEX_CLIENT_ID?.trim() || null,
    devTools: env.DEV || env.VITE_DEV_TOOLS === 'true',
  }
}
