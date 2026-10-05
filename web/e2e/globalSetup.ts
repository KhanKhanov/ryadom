// Перед тестами — понятная ошибка, если backend не запущен, вместо таймаута посреди звонка.

/** Адрес backend, на который `vite preview` пересылает /api (vite.config.ts). */
const BACKEND_URL = process.env.E2E_BACKEND_URL ?? 'http://localhost:8080'

export default async function globalSetup() {
  const healthy = await fetch(`${BACKEND_URL}/health`).then(
    (response) => response.ok,
    () => false,
  )
  if (!healthy) {
    throw new Error(
      `Backend не отвечает на ${BACKEND_URL}/health. Запустите docker compose -f infra/docker-compose.yml up ` +
        'и ./gradlew :backend:run (с AUTH_DEV_ENABLED=true) — README, «Сквозной тест».',
    )
  }
}
