import { useCallback, useRef, useState, type ReactNode } from 'react'
import type { Language } from '../i18n'
import { AnnounceContext, LanguageContext, ServicesContext, type AnnouncePriority, type AppServices } from './context'

type AppProvidersProps = {
  services: AppServices
  language: Language
  children: ReactNode
}

/** Подключает сервисы, язык и объявления для экранного диктора (см. context.ts). */
export function AppProviders({ services, language, children }: AppProvidersProps) {
  return (
    <ServicesContext.Provider value={services}>
      <LanguageContext.Provider value={language}>
        <Announcer>{children}</Announcer>
      </LanguageContext.Provider>
    </ServicesContext.Provider>
  )
}

type Message = { text: string; id: number }

/**
 * Невидимые «живые области» (aria-live): всё, что в них попадает, диктор зачитывает.
 * `polite` ждёт, пока диктор договорит; `assertive` перебивает — только для срочного (входящий вызов).
 */
function Announcer({ children }: { children: ReactNode }) {
  const [polite, setPolite] = useState<Message>({ text: '', id: 0 })
  const [assertive, setAssertive] = useState<Message>({ text: '', id: 0 })
  const counter = useRef(0)
  const announce = useCallback((text: string, priority: AnnouncePriority = 'polite') => {
    counter.current += 1
    const message = { text, id: counter.current }
    if (priority === 'assertive') setAssertive(message)
    else setPolite(message)
  }, [])
  return (
    <AnnounceContext.Provider value={announce}>
      {children}
      {/* Новый key — новый текстовый узел: диктор прочитает и повторное одинаковое сообщение. */}
      <div className="visually-hidden" role="status" aria-live="polite" data-testid="announcer-polite">
        <span key={polite.id}>{polite.text}</span>
      </div>
      <div className="visually-hidden" aria-live="assertive" data-testid="announcer-assertive">
        <span key={assertive.id}>{assertive.text}</span>
      </div>
    </AnnounceContext.Provider>
  )
}
