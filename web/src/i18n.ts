// Все строки интерфейса — здесь, на русском и английском. Не пишите текст прямо в компонентах.

export type Language = 'ru' | 'en'

export const strings = {
  ru: {
    appName: 'Рядом',
    welcomeDescription: 'Видеопомощь незрячим. Кабинет волонтёра в разработке.',
    sourceCode: 'Исходный код',
  },
  en: {
    appName: 'Ryadom',
    welcomeDescription: 'Video assistance for blind people. The volunteer app is under development.',
    sourceCode: 'Source code',
  },
} satisfies Record<Language, Record<string, string>>

/** Выбирает язык по настройкам браузера; по умолчанию — русский. */
export function detectLanguage(preferred: readonly string[]): Language {
  for (const tag of preferred) {
    const base = tag.toLowerCase().split('-')[0]
    if (base === 'ru' || base === 'en') return base
  }
  return 'ru'
}

/**
 * Применяет язык ко всей странице: атрибут lang (по нему экранный диктор выбирает голос)
 * и заголовок вкладки. В index.html заголовок на русском — он виден только до загрузки скрипта.
 */
export function applyLanguageToDocument(doc: Document, language: Language): void {
  doc.documentElement.lang = language
  doc.title = strings[language].appName
}
