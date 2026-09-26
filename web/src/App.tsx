import { strings, type Language } from './i18n'

type AppProps = {
  language: Language
}

// Временная стартовая страница (этап 0). Кабинет волонтёра появится на этапе 3.
export function App({ language }: AppProps) {
  const t = strings[language]
  return (
    <main>
      <h1>{t.appName}</h1>
      <p>{t.welcomeDescription}</p>
    </main>
  )
}
