// WebSocket событий /ws (docs/api/openapi.yaml, операция connectRealtime).

import type { AccessToken } from './client'
import { SessionEndedError } from './errors'
import { isObject, parseHelpRequest, type HelpRequest } from './types'

/** Коды закрытия соединения сервером. */
const CLOSE_UNAUTHORIZED = 4401
const CLOSE_BANNED = 4403

/** Паузы перед повторными попытками подключиться: чем дольше нет связи, тем реже попытки. */
const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 30_000]

/** За сколько до истечения access-токена отправить в соединение новый (иначе сервер закроет его). */
const REAUTH_BEFORE_EXPIRY_MS = 60_000

/** Если обновить токен не удалось (нет сети), следующая попытка — через это время. */
const REAUTH_RETRY_MS = 10_000

export const REALTIME_EVENT_TYPES = [
  'request.incoming',
  'request.accepted',
  'request.taken',
  'request.cancelled',
  'request.no_answer',
  'request.ended',
] as const

export type RealtimeEventType = (typeof REALTIME_EVENT_TYPES)[number]

/** Событие о запросе помощи: [request] — его актуальное состояние. */
export type RealtimeEvent = {
  type: RealtimeEventType
  request: HelpRequest
}

/**
 * - `connecting` — первое подключение;
 * - `connected` — вход выполнен, события приходят;
 * - `reconnecting` — связь пропала, ждём следующей попытки.
 */
export type RealtimeStatus = 'connecting' | 'connected' | 'reconnecting'

export type RealtimeHandlers = {
  /**
   * Соединение готово — в том числе после переподключения. События, случившиеся без связи,
   * сервер не повторяет, поэтому здесь клиент перечитывает состояние через REST API.
   */
  onReady(): void
  onEvent(event: RealtimeEvent): void
  onStatus(status: RealtimeStatus): void
}

/** Что нужно соединению от клиента API. */
export type RealtimeAuth = {
  accessToken(minValidityMs?: number): Promise<AccessToken>
  refreshNow(): Promise<AccessToken>
  endSession(reason: 'banned'): unknown
}

export type RealtimeOptions = {
  url: string
  auth: RealtimeAuth
  handlers: RealtimeHandlers
  /** Конструктор WebSocket; в тестах — поддельный. */
  createSocket?: (url: string) => WebSocket
  now?: () => number
}

/**
 * Соединение с сервером событий. Само входит (первым сообщением `auth`), переподключается
 * после обрывов и отправляет новый токен до истечения старого.
 * Неизвестные сообщения пропускает: их может прислать новая версия сервера.
 */
export class RealtimeConnection {
  private readonly url: string
  private readonly auth: RealtimeAuth
  private readonly handlers: RealtimeHandlers
  private readonly createSocket: (url: string) => WebSocket
  private readonly now: () => number

  private socket: WebSocket | null = null
  private running = false
  /** Сколько попыток подряд не дошли до `ready`. */
  private failures = 0
  /** Сервер отверг токен (4401): при следующем подключении сначала обновить его. */
  private forceRefresh = false
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined
  private reauthTimer: ReturnType<typeof setTimeout> | undefined

  constructor(options: RealtimeOptions) {
    this.url = options.url
    this.auth = options.auth
    this.handlers = options.handlers
    this.createSocket = options.createSocket ?? ((url) => new WebSocket(url))
    this.now = options.now ?? Date.now
  }

  start(): void {
    if (this.running) return
    this.running = true
    globalThis.addEventListener?.('online', this.reconnectNow)
    this.open()
  }

  stop(): void {
    this.running = false
    globalThis.removeEventListener?.('online', this.reconnectNow)
    clearTimeout(this.reconnectTimer)
    clearTimeout(this.reauthTimer)
    const socket = this.socket
    this.socket = null
    socket?.close(1000)
  }

  /** Сеть появилась — не ждать окончания паузы перед следующей попыткой. */
  private readonly reconnectNow = () => {
    if (!this.running || this.socket) return
    clearTimeout(this.reconnectTimer)
    this.open()
  }

  private open() {
    this.handlers.onStatus(this.failures === 0 ? 'connecting' : 'reconnecting')
    const socket = this.createSocket(this.url)
    this.socket = socket
    socket.onopen = () => void this.authenticate(socket)
    socket.onmessage = (event: MessageEvent) => this.onMessage(socket, event.data)
    socket.onclose = (event: CloseEvent) => this.onClose(socket, event.code)
  }

  /** Отправляет access-токен: первым сообщением после открытия и потом перед каждым истечением токена. */
  private async authenticate(socket: WebSocket) {
    try {
      const token = this.forceRefresh ? await this.auth.refreshNow() : await this.auth.accessToken(REAUTH_BEFORE_EXPIRY_MS)
      this.forceRefresh = false
      if (socket !== this.socket) return
      socket.send(JSON.stringify({ type: 'auth', accessToken: token.value }))
      this.scheduleReauth(socket, token.expiresAt - REAUTH_BEFORE_EXPIRY_MS - this.now())
    } catch (error) {
      if (socket !== this.socket) return
      // Сеанс закончился — переподключаться незачем, интерфейс уже показывает вход.
      if (error instanceof SessionEndedError) return this.stop()
      // Нет сети для обновления токена. Пока старый токен действует, соединение живёт; попробуем позже.
      this.scheduleReauth(socket, REAUTH_RETRY_MS)
    }
  }

  private scheduleReauth(socket: WebSocket, delayMs: number) {
    clearTimeout(this.reauthTimer)
    this.reauthTimer = setTimeout(() => void this.authenticate(socket), Math.max(delayMs, 0))
  }

  private onMessage(socket: WebSocket, data: unknown) {
    if (socket !== this.socket || typeof data !== 'string') return
    let message: unknown
    try {
      message = JSON.parse(data)
    } catch {
      return
    }
    if (!isObject(message) || typeof message.type !== 'string') return
    if (message.type === 'ready') {
      this.failures = 0
      this.handlers.onStatus('connected')
      this.handlers.onReady()
      return
    }
    const type = REALTIME_EVENT_TYPES.find((known) => known === message.type)
    if (!type) return // Новый вид события — этот клиент о нём не знает.
    try {
      this.handlers.onEvent({ type, request: parseHelpRequest(message.request) })
    } catch {
      // Событие не разобрать — пропускаем; актуальное состояние клиент перечитает после переподключения.
    }
  }

  private onClose(socket: WebSocket, code: number) {
    if (socket !== this.socket) return
    this.socket = null
    clearTimeout(this.reauthTimer)
    if (!this.running) return
    if (code === CLOSE_BANNED) {
      this.stop()
      this.auth.endSession('banned')
      return
    }
    // Токен отвергнут (истёк, пока компьютер спал): обновить и сразу подключиться.
    // Если это повторяется до `ready`, дело не в токене — переподключаемся с паузами, как при обрыве.
    const unauthorized = code === CLOSE_UNAUTHORIZED
    if (unauthorized) this.forceRefresh = true
    const immediate = unauthorized && this.failures === 0
    const delay = immediate ? 0 : RECONNECT_DELAYS_MS[Math.min(this.failures, RECONNECT_DELAYS_MS.length - 1)]
    this.failures++
    this.handlers.onStatus('reconnecting')
    this.reconnectTimer = setTimeout(() => this.open(), delay)
  }
}
