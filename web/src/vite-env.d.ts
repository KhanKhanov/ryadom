/// <reference types="vite/client" />

// Переменные сборки, которые читает приложение (задаются в web/.env.local, см. README).
interface ImportMetaEnv {
  /** client_id приложения в Яндекс ID. Пусто — кнопки входа через Яндекс нет. */
  readonly VITE_YANDEX_CLIENT_ID?: string
  /** `true` — включить инструменты разработчика и в собранной версии (например, на тестовом сервере). */
  readonly VITE_DEV_TOOLS?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
