import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { applyLanguageToDocument, detectLanguage } from './i18n'

const language = detectLanguage(navigator.languages)
applyLanguageToDocument(document, language)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App language={language} />
  </StrictMode>,
)
