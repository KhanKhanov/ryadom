import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { App } from '../App'
import type { YandexCallback } from '../auth/yandex'
import type { Language } from '../i18n'
import { AppProviders } from '../ui/AppProviders'
import type { TestServices } from './fakes'

/** Всё приложение с поддельными сервисами. */
export function renderApp(services: TestServices, options: { language?: Language; yandexCallback?: YandexCallback | null } = {}) {
  const user = userEvent.setup()
  const result = render(<App language={options.language ?? 'ru'} services={services} yandexCallback={options.yandexCallback ?? null} />)
  return { user, ...result }
}

/** Отдельный экран с поддельными сервисами. */
export function renderScreen(services: TestServices, ui: ReactNode, language: Language = 'ru') {
  const user = userEvent.setup()
  const result = render(
    <AppProviders services={services} language={language}>
      {ui}
    </AppProviders>,
  )
  return { user, ...result }
}

/** Что сейчас объявлено экранным диктором. */
export function announced(priority: 'polite' | 'assertive' = 'polite'): string {
  return screen.getByTestId(`announcer-${priority}`).textContent ?? ''
}
