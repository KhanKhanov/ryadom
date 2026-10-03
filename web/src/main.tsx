import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { readYandexCallback, withoutCallbackParams, type YandexCallback } from './auth/yandex'
import { loadConfig } from './config'
import { applyLanguageToDocument, detectLanguage } from './i18n'
import { manifestPath } from './manifest'
import { createBrowserServices } from './services'
import './styles.css'

const language = detectLanguage(navigator.languages)
applyLanguageToDocument(document, language)
// Название на экране «Домой» — на языке браузера (в index.html — русский манифест).
document.querySelector('link[rel="manifest"]')?.setAttribute('href', manifestPath(language))

const config = loadConfig(window.location, import.meta.env)

// Пользователь вернулся со страницы Яндекс ID: забираем ответ и сразу убираем его из адреса.
let yandexCallback: YandexCallback | null = null
const url = new URL(window.location.href)
try {
  yandexCallback = readYandexCallback(url, window.sessionStorage)
} catch {
  // sessionStorage запрещён настройками браузера — вход через Яндекс в таком режиме не работает.
}
if (yandexCallback) window.history.replaceState(null, '', withoutCallbackParams(url))

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App language={language} services={createBrowserServices(config)} yandexCallback={yandexCallback} />
  </StrictMode>,
)
