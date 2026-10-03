// Поддельные сервер, соединение событий, звонок и звук для тестов: настоящих сети, WebRTC и звука в jsdom нет.

import { vi, type Mock } from 'vitest'
import { ApiClient } from '../api/client'
import type { RealtimeEvent, RealtimeEventType, RealtimeHandlers, RealtimeStatus } from '../api/realtime'
import { createLock, MemoryStorage, SessionStore } from '../api/session'
import { initialCallState, type CallFactory, type CallOptions, type CallSession, type CallState } from '../call/call'
import type { AppConfig } from '../config'
import type { AppServices } from '../ui/context'
import type { Ringer } from '../ui/ringer'

/** Хранилище в памяти — вместо localStorage и sessionStorage. */
export { MemoryStorage }

export type RecordedRequest = {
  method: string
  path: string
  body: unknown
  /** Значение заголовка Authorization. */
  authorization: string | null
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>

/** Ответ сервера в формате JSON. */
export function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Ответ с ошибкой в формате API. */
export function errorResponse(status: number, code: string): Response {
  return jsonResponse(status, { code, message: `test ${code}` })
}

/**
 * Поддельный сервер API. Обработчики задаются методом [on]; запрос без обработчика — 404.
 * Все запросы записываются в [requests].
 */
export class FakeBackend {
  readonly requests: RecordedRequest[] = []
  private readonly handlers = new Map<string, Handler>()

  on(method: string, path: string, handler: Handler | Response | (() => Response)): this {
    this.handlers.set(`${method} ${path}`, typeof handler === 'function' ? handler : () => handler.clone())
    return this
  }

  /** Запросы с этим методом и путём. */
  calls(method: string, path: string): RecordedRequest[] {
    return this.requests.filter((r) => r.method === method && r.path === path)
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace(/^\/api/, '')
    const headers = new Headers(init?.headers)
    const request: RecordedRequest = {
      method: init?.method ?? 'GET',
      path,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      authorization: headers.get('Authorization'),
    }
    this.requests.push(request)
    const handler = this.handlers.get(`${request.method} ${path}`)
    if (!handler) return errorResponse(404, 'not_found')
    return handler(request)
  }
}

/** Профиль из ответа сервера; поля можно заменить. */
export function profileJson(overrides: Record<string, unknown> = {}) {
  return {
    id: 'user-1',
    role: 'volunteer',
    displayName: 'Анна',
    languages: ['ru'],
    gender: 'unspecified',
    genderPreference: 'any',
    timezone: 'Europe/Moscow',
    doNotDisturb: { from: '22:00', to: '08:00' },
    notificationsEnabled: true,
    createdAt: '2026-10-01T10:00:00Z',
    ...overrides,
  }
}

/** Ответ на вход. */
export function authJson(overrides: { accessToken?: string; refreshToken?: string; expiresIn?: number; user?: Record<string, unknown> } = {}) {
  return {
    accessToken: overrides.accessToken ?? 'access-1',
    accessTokenExpiresIn: overrides.expiresIn ?? 900,
    refreshToken: overrides.refreshToken ?? 'refresh-1',
    user: profileJson(overrides.user),
  }
}

/** Запрос помощи из ответа сервера. */
export function requestJson(overrides: Record<string, unknown> = {}) {
  return {
    id: 'request-1',
    status: 'searching',
    language: 'ru',
    genderPreference: 'any',
    createdAt: '2026-10-03T10:00:00Z',
    acceptedAt: null,
    endedAt: null,
    call: null,
    ...overrides,
  }
}

export const callJson = { url: 'ws://localhost:7880', room: 'request-1', token: 'livekit-token' }

/** Поддельное соединение событий: тест сам «присылает» события. */
export class FakeRealtime {
  handlers: RealtimeHandlers | null = null
  connections = 0
  stopped = 0

  readonly connect = (handlers: RealtimeHandlers) => {
    this.handlers = handlers
    this.connections++
    return { stop: () => this.stopped++ }
  }

  status(status: RealtimeStatus) {
    this.opened().onStatus(status)
  }

  /** Соединение готово (`ready`). */
  ready() {
    this.opened().onStatus('connected')
    this.opened().onReady()
  }

  emit(type: RealtimeEventType, request: RealtimeEvent['request']) {
    this.opened().onEvent({ type, request })
  }

  /** Без открытого соединения событие ушло бы в пустоту, и тест упал бы позже и непонятно где. */
  private opened(): RealtimeHandlers {
    if (!this.handlers) throw new Error('FakeRealtime: the screen has not connected yet — wait until connections > 0')
    return this.handlers
  }
}

/** Поддельный звонок: тест меняет его состояние через [update]. */
export class FakeCall implements CallSession {
  readonly credentials
  readonly options: CallOptions
  connected = false
  disconnected = false
  microphoneCalls: boolean[] = []
  audioStarted = false
  readonly videos: Record<'remote' | 'local', HTMLVideoElement | null> = { remote: null, local: null }
  private state: CallState = initialCallState
  private readonly listeners = new Set<(state: CallState) => void>()

  constructor(credentials: Parameters<CallFactory>[0], options: CallOptions) {
    this.credentials = credentials
    this.options = options
  }

  async connect() {
    this.connected = true
  }
  async disconnect() {
    this.disconnected = true
  }
  /** Браузер не даёт доступа к микрофону. */
  microphoneBlocked = false

  async setMicrophoneEnabled(enabled: boolean) {
    this.microphoneCalls.push(enabled)
    if (enabled && this.microphoneBlocked) {
      this.update({ microphone: 'blocked' })
      return false
    }
    this.update({ microphone: enabled ? 'on' : 'muted' })
    return true
  }
  async startAudio() {
    this.audioStarted = true
    this.update({ audioBlocked: false })
  }
  attachVideo(kind: 'remote' | 'local', element: HTMLVideoElement | null) {
    this.videos[kind] = element
  }
  subscribe(listener: (state: CallState) => void) {
    this.listeners.add(listener)
    listener(this.state)
    return () => {
      this.listeners.delete(listener)
    }
  }

  update(patch: Partial<CallState>) {
    this.state = { ...this.state, ...patch }
    for (const listener of this.listeners) listener(this.state)
  }
}

/** Все созданные звонки; последний — [last]. */
export class FakeCalls {
  readonly all: FakeCall[] = []
  readonly create: CallFactory = (credentials, options) => {
    const call = new FakeCall(credentials, options)
    this.all.push(call)
    return call
  }
  get last(): FakeCall {
    const call = this.all.at(-1)
    if (!call) throw new Error('No call was created')
    return call
  }
}

export type FakeRinger = Ringer & { start: Mock<() => void>; stop: Mock<() => void>; test: Mock<() => void> }

export function fakeRinger(): FakeRinger {
  return { start: vi.fn<() => void>(), stop: vi.fn<() => void>(), test: vi.fn<() => void>() }
}

export const testConfig: AppConfig = {
  apiBaseUrl: '/api',
  realtimeUrl: 'ws://localhost/api/ws',
  yandexRedirectUri: 'http://localhost/',
  yandexClientId: null,
  devTools: false,
}

export type TestServices = AppServices & {
  backend: FakeBackend
  realtime: FakeRealtime
  calls: FakeCalls
  ringer: FakeRinger
  storage: MemoryStorage
  navigate: Mock<(url: string) => void>
}

/** Сервисы для тестов экранов. [loggedIn] — сохранить токены, как будто пользователь уже входил. */
export function createTestServices(options: { config?: Partial<AppConfig>; loggedIn?: boolean; now?: Date } = {}): TestServices {
  const backend = new FakeBackend()
  const storage = new MemoryStorage()
  const store = new SessionStore(storage)
  if (options.loggedIn) {
    store.set({ userId: 'user-1', accessToken: 'access-1', accessTokenExpiresAt: Date.now() + 15 * 60_000, refreshToken: 'refresh-1' })
  }
  const api = new ApiClient({ baseUrl: '/api', store, lock: createLock(null), fetch: backend.fetch })
  const realtime = new FakeRealtime()
  const calls = new FakeCalls()
  const now = options.now ?? new Date('2026-10-03T12:00:00Z')
  return {
    config: { ...testConfig, ...options.config },
    api,
    connectRealtime: realtime.connect,
    createCall: calls.create,
    ringer: fakeRinger(),
    tabStorage: new MemoryStorage(),
    navigate: vi.fn<(url: string) => void>(),
    now: () => now,
    backend,
    realtime,
    calls,
    storage,
  }
}
