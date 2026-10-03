// Вход через Яндекс ID в браузере: код авторизации с PKCE (RFC 7636).
//
// 1. startYandexLogin() запоминает случайные code_verifier и state и возвращает адрес страницы Яндекса.
// 2. Яндекс возвращает пользователя на redirect_uri с ?code=…&state=… (или ?error=…).
// 3. readYandexCallback() проверяет state и отдаёт код с code_verifier — их получает сервер
//    (POST /auth/oauth/yandex) и сам обменивает код на токен Яндекса.
// PKCE защищает от перехвата кода: без code_verifier, который не покидал эту вкладку, код бесполезен.
// state защищает от подмены: вход завершается, только если его начала эта же вкладка.

const AUTHORIZE_URL = 'https://oauth.yandex.ru/authorize'

/** Ключ в sessionStorage: данные начатого входа живут только в этой вкладке. */
const PENDING_KEY = 'ryadom.yandexLogin'

type PendingLogin = {
  state: string
  codeVerifier: string
}

export type YandexCallback =
  | { kind: 'code'; code: string; codeVerifier: string }
  /** `denied` — пользователь отказался; `failed` — ошибка Яндекса или чужой ответ (state не совпал). */
  | { kind: 'error'; error: 'denied' | 'failed' }

/** Начинает вход: возвращает адрес, на который нужно перейти. */
export async function startYandexLogin(clientId: string, redirectUri: string, storage: Storage): Promise<string> {
  const pending: PendingLogin = { state: randomUrlSafeString(16), codeVerifier: randomUrlSafeString(32) }
  storage.setItem(PENDING_KEY, JSON.stringify(pending))
  const url = new URL(AUTHORIZE_URL)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', clientId)
  url.searchParams.set('redirect_uri', redirectUri)
  url.searchParams.set('state', pending.state)
  url.searchParams.set('code_challenge', await codeChallenge(pending.codeVerifier))
  url.searchParams.set('code_challenge_method', 'S256')
  return url.toString()
}

/** Ответ Яндекса в адресе страницы; `null` — это обычный заход на сайт, а не возврат от Яндекса. */
export function readYandexCallback(url: URL, storage: Storage): YandexCallback | null {
  const params = url.searchParams
  const code = params.get('code')
  const error = params.get('error')
  if (code === null && error === null) return null

  const pending = takePending(storage)
  if (!pending || params.get('state') !== pending.state) return { kind: 'error', error: 'failed' }
  if (error === 'access_denied') return { kind: 'error', error: 'denied' }
  if (code === null || code === '') return { kind: 'error', error: 'failed' }
  return { kind: 'code', code, codeVerifier: pending.codeVerifier }
}

/** Убирает из адреса параметры ответа Яндекса — чтобы код не остался в истории браузера. */
export function withoutCallbackParams(url: URL): string {
  const clean = new URL(url)
  for (const name of ['code', 'state', 'error', 'error_description', 'cid']) clean.searchParams.delete(name)
  return clean.pathname + clean.search + clean.hash
}

function takePending(storage: Storage): PendingLogin | null {
  const raw = storage.getItem(PENDING_KEY)
  storage.removeItem(PENDING_KEY)
  if (!raw) return null
  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null
    const { state, codeVerifier } = value as Record<string, unknown>
    return typeof state === 'string' && typeof codeVerifier === 'string' ? { state, codeVerifier } : null
  } catch {
    return null
  }
}

/** code_challenge для метода S256: BASE64URL(SHA-256(code_verifier)). */
export async function codeChallenge(codeVerifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(codeVerifier))
  return base64Url(new Uint8Array(digest))
}

/** Случайная строка из [byteCount] байт в BASE64URL; 32 байта дают 43 символа — минимум для code_verifier. */
export function randomUrlSafeString(byteCount: number): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(byteCount)))
}

function base64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
