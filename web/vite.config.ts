/// <reference types="vitest/config" />
import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import { pwaFiles } from './vite.pwa.ts'

/**
 * Сайт и API на одном адресе: запросы к /api/... Vite пересылает на backend (./gradlew :backend:run),
 * убирая /api из пути, — в том числе WebSocket /api/ws. На сервере то же самое делает Caddy (этап 9).
 */
const apiProxy: Record<string, ProxyOptions> = {
  '/api': {
    target: 'http://localhost:8080',
    ws: true,
    rewrite: (path) => path.replace(/^\/api/, ''),
  },
}

// Vite собирает веб-приложение, Vitest запускает тесты в эмуляции браузера (jsdom).
export default defineConfig({
  // pwaFiles — Service Worker для push-уведомлений и манифесты веб-приложения (vite.pwa.ts).
  plugins: [react(), pwaFiles()],
  server: { proxy: apiProxy },
  preview: { proxy: apiProxy },
  // LiveKit — один неделимый кусок около 520 КБ; он загружается только при звонке (src/call/deferredCall.ts).
  build: { chunkSizeWarningLimit: 600 },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
})
