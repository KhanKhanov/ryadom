// Сквозной тест звонка в браузере (Playwright): незрячий («тестовый незрячий» сайта) просит помощи,
// волонтёр в кабинете принимает вызов и видит его камеру. Проверяет то, что юнит-тесты подменяют
// поддельными: backend, WebSocket, прокси /api, LiveKit и его webhook.
//
// Запуск — README, «Сквозной тест». Перед ним нужны PostgreSQL и LiveKit (docker compose)
// и backend на порту 8080 с AUTH_DEV_ENABLED=true; сайт тест собирает и запускает сам.
import { defineConfig, devices } from '@playwright/test'

/** Где сайт. По умолчанию тест сам собирает его и запускает `vite preview` (/api пересылается на backend). */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173'

export default defineConfig({
  testDir: './e2e',
  testMatch: '*.e2e.ts',
  // Звонок: поиск волонтёра, подключение к LiveKit, видео.
  timeout: 120_000,
  expect: { timeout: 20_000 },
  // Один звонок за раз: тесты делят сервер и волонтёров.
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  globalSetup: './e2e/globalSetup.ts',
  use: {
    baseURL,
    locale: 'ru-RU',
    // Камера и микрофон — поддельные: Chrome рисует тестовую картинку и подаёт тон вместо звука.
    permissions: ['camera', 'microphone'],
    launchOptions: {
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
    },
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run build && npm run preview -- --port 4173 --strictPort',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
        // Вход без OAuth и «тестовый незрячий» есть только в сборке с этим флагом.
        env: { VITE_DEV_TOOLS: 'true' },
      },
})
