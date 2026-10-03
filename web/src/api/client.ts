// Клиент REST API (docs/api/openapi.yaml): вход, профиль, запросы помощи, push-уведомления.

import { ApiError, ErrorCodes, NetworkError, SessionEndedError, type SessionEndReason } from './errors'
import type { LockRunner, SessionStore, StoredSession } from './session'
import {
  isObject,
  parseAuthResponse,
  parseDeviceId,
  parseHelpRequest,
  parseIncomingRequests,
  parseProfile,
  parsePushConfig,
  UnexpectedResponseError,
  type AuthResponse,
  type HelpRequest,
  type ProfileUpdate,
  type PushConfig,
  type UserProfile,
  type WebPushSubscription,
} from './types'

/** Access-токен обновляется заранее, если до его истечения осталось меньше этого. */
const MIN_TOKEN_VALIDITY_MS = 30_000

/** Имя блокировки обновления токенов — общее для всех вкладок сайта. */
const REFRESH_LOCK = 'ryadom-token-refresh'

/** Access-токен и время его истечения (миллисекунды Unix-времени). */
export type AccessToken = {
  value: string
  expiresAt: number
}

/**
 * Что случилось с сеансом:
 * - `ended` — нужно войти снова (см. [SessionEndReason]);
 * - `user_changed` — в другой вкладке вошли под другим пользователем, профиль нужно перечитать.
 */
export type SessionEvent = { kind: 'ended'; reason: SessionEndReason } | { kind: 'user_changed' }

export type ApiClientOptions = {
  /** Начало адресов API, например `/api`. */
  baseUrl: string
  store: SessionStore
  lock: LockRunner
  fetch?: typeof fetch
  now?: () => number
}

/**
 * Обращения к серверу. Сам добавляет access-токен, заранее обновляет его и повторяет запрос,
 * если сервер ответил 401. Если войти заново нужно самому пользователю (refresh-токен недействителен,
 * пользователь заблокирован), удаляет токены, сообщает подписчикам [onSessionEvent]
 * и бросает [SessionEndedError].
 */
export class ApiClient {
  private readonly baseUrl: string
  private readonly store: SessionStore
  private readonly lock: LockRunner
  private readonly fetchImpl: typeof fetch
  private readonly now: () => number
  private readonly listeners = new Set<(event: SessionEvent) => void>()
  private readonly beforeLogout = new Set<() => Promise<unknown>>()
  /** Пользователь, под которым работает эта вкладка. */
  private userId: string | null

  constructor(options: ApiClientOptions) {
    this.baseUrl = options.baseUrl
    this.store = options.store
    this.lock = options.lock
    // Обёртка нужна: fetch, вызванный не от window, в браузере падает с «Illegal invocation».
    this.fetchImpl = options.fetch ?? ((input, init) => fetch(input, init))
    this.now = options.now ?? Date.now
    this.userId = this.store.get()?.userId ?? null
  }

  /** Есть ли сохранённый вход (токены могли истечь — это выяснится при первом запросе). */
  hasSession(): boolean {
    return this.store.get() !== null
  }

  // --- Вход ---

  /** Вход без OAuth, только если на сервере AUTH_DEV_ENABLED=true. */
  loginDev(login: string): Promise<UserProfile> {
    return this.login('/auth/dev', { login })
  }

  /** Вход через Яндекс ID: код авторизации и PKCE code_verifier из браузера. */
  loginYandex(code: string, codeVerifier: string): Promise<UserProfile> {
    return this.login('/auth/oauth/yandex', { code, codeVerifier })
  }

  /**
   * Выход на этом устройстве: сначала задачи [onBeforeLogout], пока вход ещё действует, затем токены
   * удаляются, а сервер отзывает refresh-токен.
   */
  async logout(): Promise<void> {
    const session = this.store.get()
    // Ошибка задачи (нет связи) выход не останавливает.
    if (session) await Promise.allSettled([...this.beforeLogout].map((task) => task()))
    this.endSession('logged_out')
    if (!session) return
    try {
      await this.send('POST', '/auth/logout', { refreshToken: session.refreshToken })
    } catch {
      // Токены уже удалены. Если сервер недоступен, refresh-токен просто истечёт.
    }
  }

  // --- Профиль ---

  async getMe(): Promise<UserProfile> {
    const profile = parseProfile(await this.json('GET', '/me'))
    this.userId = profile.id
    return profile
  }

  async updateMe(update: ProfileUpdate): Promise<UserProfile> {
    return parseProfile(await this.json('PATCH', '/me', update))
  }

  // --- Запросы помощи ---

  /** Активный запрос или идущий звонок пользователя; `null` — нет. */
  async getCurrentRequest(): Promise<HelpRequest | null> {
    const response = await this.authorized('GET', '/requests/current')
    if (response.status === 204) return null
    return parseHelpRequest(await readJson(response))
  }

  /**
   * Вызовы, которые ждут ответа волонтёра. События без соединения сервер не повторяет, поэтому
   * список перечитывается после каждого подключения и при нажатии на уведомление.
   */
  async getIncomingRequests(): Promise<HelpRequest[]> {
    return parseIncomingRequests(await this.json('GET', '/requests/incoming'))
  }

  async getRequest(id: string): Promise<HelpRequest> {
    return parseHelpRequest(await this.json('GET', requestPath(id)))
  }

  /** Незрячий просит помощи (в веб-версии — только тестовый незрячий для разработки). */
  async createRequest(): Promise<HelpRequest> {
    return parseHelpRequest(await this.json('POST', '/requests', {}))
  }

  /** Незрячий отменяет поиск или завершает звонок; волонтёр завершает звонок. */
  async cancelRequest(id: string): Promise<HelpRequest> {
    return parseHelpRequest(await this.json('DELETE', requestPath(id)))
  }

  /** Волонтёр принимает запрос; в ответе — данные для входа в звонок. */
  async acceptRequest(id: string): Promise<HelpRequest> {
    return parseHelpRequest(await this.json('POST', `${requestPath(id)}/accept`))
  }

  async rateRequest(id: string, helped: boolean): Promise<void> {
    await this.authorized('POST', `${requestPath(id)}/rating`, { helped })
  }

  // --- Push-уведомления ---

  async getPushConfig(): Promise<PushConfig> {
    return parsePushConfig(await this.json('GET', '/push/config'))
  }

  /** Сохраняет подписку браузера на сервере; возвращает id устройства. Повторная регистрация безопасна. */
  async registerWebPush(subscription: WebPushSubscription): Promise<string> {
    const body = {
      provider: 'webpush',
      token: subscription.endpoint,
      webPush: { p256dh: subscription.p256dh, auth: subscription.auth },
    }
    return parseDeviceId(await this.json('POST', '/devices', body))
  }

  /** Выключает push-уведомления на устройстве. Повторный вызов безопасен. */
  async deleteDevice(id: string): Promise<void> {
    await this.authorized('DELETE', `/devices/${encodeURIComponent(id)}`)
  }

  // --- Токены и сеанс ---

  /** Access-токен, который будет действовать ещё хотя бы [minValidityMs]; при необходимости обновляет токены. */
  async accessToken(minValidityMs = MIN_TOKEN_VALIDITY_MS): Promise<AccessToken> {
    const session = this.store.get()
    if (!session) throw this.endSession('logged_out')
    if (session.accessTokenExpiresAt - this.now() > minValidityMs) return tokenOf(session)
    return this.refresh(session.accessToken, minValidityMs)
  }

  /** Обновляет токены, даже если access-токен ещё не истёк: сервер его не принял (WebSocket закрыт с кодом 4401). */
  async refreshNow(): Promise<AccessToken> {
    const session = this.store.get()
    if (!session) throw this.endSession('logged_out')
    return this.refresh(session.accessToken, MIN_TOKEN_VALIDITY_MS)
  }

  /** Удаляет токены и сообщает подписчикам, что сеанс закончился. */
  endSession(reason: SessionEndReason): SessionEndedError {
    this.store.clear()
    this.userId = null
    this.emit({ kind: 'ended', reason })
    return new SessionEndedError(reason)
  }

  onSessionEvent(listener: (event: SessionEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /**
   * Задача, которую нужно выполнить при выходе, пока вход ещё действует, — например, завершить звонок
   * этой вкладки: иначе он остался бы открытым на сервере. Возвращает функцию отмены.
   */
  onBeforeLogout(task: () => Promise<unknown>): () => void {
    this.beforeLogout.add(task)
    return () => this.beforeLogout.delete(task)
  }

  /** Следит за входом и выходом в других вкладках этого сайта. */
  watchOtherTabs(target: Pick<Window, 'addEventListener' | 'removeEventListener'>): () => void {
    return this.store.subscribe(target, () => {
      const session = this.store.get()
      if (!session) {
        if (this.userId !== null) {
          this.userId = null
          this.emit({ kind: 'ended', reason: 'logged_out' })
        }
      } else if (session.userId !== this.userId) {
        this.userId = session.userId
        this.emit({ kind: 'user_changed' })
      }
    })
  }

  private emit(event: SessionEvent) {
    for (const listener of [...this.listeners]) listener(event)
  }

  private async login(path: string, body: unknown): Promise<UserProfile> {
    const response = await this.send('POST', path, body)
    if (!response.ok) throw await readError(response)
    const auth = parseAuthResponse(await readJson(response))
    this.save(auth)
    return auth.user
  }

  /**
   * Обновляет токены под блокировкой, общей для всех вкладок. [staleAccessToken] — токен, который
   * не подошёл или истекает: если за время ожидания блокировки его уже заменили (другая вкладка
   * или другой запрос этой вкладки), второй раз обновлять не нужно.
   */
  private refresh(staleAccessToken: string, minValidityMs: number): Promise<AccessToken> {
    return this.lock(REFRESH_LOCK, async () => {
      const session = this.store.get()
      if (!session) throw this.endSession('logged_out')
      if (session.accessToken !== staleAccessToken && session.accessTokenExpiresAt - this.now() > minValidityMs) {
        return tokenOf(session)
      }
      const response = await this.send('POST', '/auth/refresh', { refreshToken: session.refreshToken })
      if (response.ok) return tokenOf(this.save(parseAuthResponse(await readJson(response))))
      const error = await readError(response)
      if (response.status === 401) throw this.endSession('expired')
      if (error instanceof ApiError && error.code === ErrorCodes.userBanned) throw this.endSession('banned')
      throw error
    })
  }

  private save(auth: AuthResponse): StoredSession {
    const session: StoredSession = {
      userId: auth.user.id,
      accessToken: auth.accessToken,
      accessTokenExpiresAt: this.now() + auth.accessTokenExpiresIn * 1000,
      refreshToken: auth.refreshToken,
    }
    this.store.set(session)
    this.userId = session.userId
    return session
  }

  /** Запрос от имени пользователя. Ответ 401 — один раз обновить токены и повторить. */
  private async authorized(method: string, path: string, body?: unknown): Promise<Response> {
    let token = await this.accessToken()
    let response = await this.send(method, path, body, token.value)
    if (response.status === 401) {
      token = await this.refresh(token.value, MIN_TOKEN_VALIDITY_MS)
      response = await this.send(method, path, body, token.value)
      if (response.status === 401) throw this.endSession('expired')
    }
    if (!response.ok) {
      const error = await readError(response)
      if (error instanceof ApiError && error.code === ErrorCodes.userBanned) throw this.endSession('banned')
      throw error
    }
    return response
  }

  private async json(method: string, path: string, body?: unknown): Promise<unknown> {
    return readJson(await this.authorized(method, path, body))
  }

  private async send(method: string, path: string, body?: unknown, accessToken?: string): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (accessToken) headers.Authorization = `Bearer ${accessToken}`
    try {
      return await this.fetchImpl(this.baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch (error) {
      throw new NetworkError(error)
    }
  }
}

function tokenOf(session: StoredSession): AccessToken {
  return { value: session.accessToken, expiresAt: session.accessTokenExpiresAt }
}

function requestPath(id: string): string {
  return `/requests/${encodeURIComponent(id)}`
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    throw new UnexpectedResponseError(`Response to ${response.url} is not JSON`)
  }
}

/**
 * Ошибка из ответа сервера. Код неизвестен клиенту — не беда: интерфейс покажет общее сообщение.
 * Ответ 5xx без тела в формате API — это не наш сервер, а прокси перед ним (backend выключен
 * или перезапускается), поэтому для пользователя это «нет связи с сервером».
 */
async function readError(response: Response): Promise<Error> {
  let body: unknown = null
  try {
    body = await response.json()
  } catch {
    // Тело не JSON.
  }
  if (isObject(body) && typeof body.code === 'string') {
    return new ApiError(response.status, body.code, typeof body.message === 'string' ? body.message : '')
  }
  if (response.status >= 500) return new NetworkError(`HTTP ${response.status}`)
  return new ApiError(response.status, 'unknown', `HTTP ${response.status}`)
}
