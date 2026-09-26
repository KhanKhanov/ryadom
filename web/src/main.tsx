import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { detectLanguage } from './i18n'

const language = detectLanguage(navigator.languages)
document.documentElement.lang = language

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App language={language} />
  </StrictMode>,
)
