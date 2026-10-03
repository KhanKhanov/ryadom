// Общие для всех экранов вещи, доступные через React-контекст: сервисы, язык, объявления.
// Сами провайдеры — в AppProviders.tsx.

import { createContext, useContext } from 'react'
import type { ApiClient } from '../api/client'
import type { RealtimeHandlers } from '../api/realtime'
import type { CallFactory } from '../call/call'
import type { AppConfig } from '../config'
import { strings, type Language, type Strings } from '../i18n'
import type { Ringer } from './ringer'

/** Всё, что экраны берут извне. В тестах подменяется поддельными реализациями (src/testing). */
export type AppServices = {
  config: AppConfig
  api: ApiClient
  /** Открывает соединение событий. Закрыть — stop(). */
  connectRealtime(handlers: RealtimeHandlers): { stop(): void }
  createCall: CallFactory
  ringer: Ringer
  /** Хранилище этой вкладки (sessionStorage, а если браузер его запретил — в памяти) — для начатого входа через Яндекс. */
  tabStorage: Storage
  /** Переход на другой сайт (страница входа Яндекса). */
  navigate(url: string): void
  now(): Date
}

/** `assertive` перебивает диктора — только для срочного, например входящего вызова. */
export type AnnouncePriority = 'polite' | 'assertive'
export type Announce = (message: string, priority?: AnnouncePriority) => void

export const ServicesContext = createContext<AppServices | null>(null)
export const LanguageContext = createContext<Language>('ru')
export const AnnounceContext = createContext<Announce>(() => undefined)

export function useServices(): AppServices {
  const services = useContext(ServicesContext)
  if (!services) throw new Error('useServices() must be used inside <AppProviders>')
  return services
}

/** Строки интерфейса на языке страницы. */
export function useStrings(): Strings {
  return strings[useContext(LanguageContext)]
}

/**
 * Объявить сообщение экранным диктором (NVDA, VoiceOver, TalkBack), не перемещая фокус:
 * изменение статуса должно быть слышно, а не только видно.
 */
export function useAnnounce(): Announce {
  return useContext(AnnounceContext)
}
