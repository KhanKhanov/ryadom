// Модели API (docs/api/openapi.yaml) и их разбор из JSON.
//
// Правила совместимости (docs/ARCHITECTURE.md, раздел 6): старые версии клиентов работают долго,
// поэтому разбор не ломается на новом сервере:
// - неизвестные поля игнорируются — берём только нужные;
// - неизвестное значение перечисления (роль, статус) превращается в 'unknown', а не в ошибку.
// Ошибка разбора (UnexpectedResponseError) — только если нет обязательного поля.

export const ROLES = ['blind', 'volunteer', 'admin'] as const
export type Role = (typeof ROLES)[number]

/** Роль, которую пользователь выбирает сам (`PATCH /me`). */
export type SelectableRole = Exclude<Role, 'admin'>

export const REQUEST_STATUSES = ['searching', 'accepted', 'in_call', 'ended', 'no_answer', 'cancelled'] as const
export type RequestStatus = (typeof REQUEST_STATUSES)[number]

/** Окно «не беспокоить» по местному времени, `ЧЧ:ММ`. Если `from` равно `to`, окно пустое. */
export type DoNotDisturb = {
  from: string
  to: string
}

export type UserProfile = {
  id: string
  /** `null` — роль ещё не выбрана; `'unknown'` — роль из новой версии сервера, которую этот клиент не знает. */
  role: Role | 'unknown' | null
  displayName: string | null
  /** Часовой пояс IANA, например `Europe/Moscow`. */
  timezone: string
  doNotDisturb: DoNotDisturb
  /** Получать ли вызовы о помощи. В интерфейсе — переключатель «Готов помогать». */
  notificationsEnabled: boolean
}

/** Изменения профиля для `PATCH /me`: отсутствующее поле не меняется. */
export type ProfileUpdate = {
  role?: SelectableRole
  timezone?: string
  doNotDisturb?: DoNotDisturb
  notificationsEnabled?: boolean
}

/** Данные для входа в комнату звонка LiveKit. */
export type CallCredentials = {
  url: string
  room: string
  token: string
}

export type HelpRequest = {
  id: string
  /** `'unknown'` — статус из новой версии сервера; клиент считает такой запрос закрытым. */
  status: RequestStatus | 'unknown'
  /** Язык запроса, код ISO 639-1. */
  language: string
  /** Есть только у участников звонка, пока он идёт. */
  call: CallCredentials | null
}

export type AuthResponse = {
  accessToken: string
  /** Через сколько секунд истечёт access-токен. */
  accessTokenExpiresIn: number
  refreshToken: string
  user: UserProfile
}

/** Сервер ответил не так, как описано в openapi.yaml. */
export class UnexpectedResponseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UnexpectedResponseError'
  }
}

/** Запрос не закрыт: идёт поиск или звонок. */
export function isActive(status: HelpRequest['status']): boolean {
  return status === 'searching' || isCallActive(status)
}

/** Звонок идёт или участники в него подключаются. */
export function isCallActive(status: HelpRequest['status']): boolean {
  return status === 'accepted' || status === 'in_call'
}

export function parseProfile(value: unknown): UserProfile {
  const json = object(value, 'profile')
  const dnd = object(json.doNotDisturb, 'doNotDisturb')
  return {
    id: string(json, 'id'),
    role: json.role === null ? null : oneOf(string(json, 'role'), ROLES),
    displayName: nullableString(json, 'displayName'),
    timezone: string(json, 'timezone'),
    doNotDisturb: { from: string(dnd, 'from'), to: string(dnd, 'to') },
    notificationsEnabled: boolean(json, 'notificationsEnabled'),
  }
}

export function parseHelpRequest(value: unknown): HelpRequest {
  const json = object(value, 'request')
  return {
    id: string(json, 'id'),
    status: oneOf(string(json, 'status'), REQUEST_STATUSES),
    language: string(json, 'language'),
    call: json.call == null ? null : parseCall(json.call),
  }
}

export function parseAuthResponse(value: unknown): AuthResponse {
  const json = object(value, 'auth response')
  return {
    accessToken: string(json, 'accessToken'),
    accessTokenExpiresIn: number(json, 'accessTokenExpiresIn'),
    refreshToken: string(json, 'refreshToken'),
    user: parseProfile(json.user),
  }
}

function parseCall(value: unknown): CallCredentials {
  const json = object(value, 'call')
  return { url: string(json, 'url'), room: string(json, 'room'), token: string(json, 'token') }
}

// Небольшие помощники разбора: проверяют тип поля и бросают понятную ошибку.

type Json = Record<string, unknown>

export function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function object(value: unknown, what: string): Json {
  if (!isObject(value)) throw new UnexpectedResponseError(`${what} must be an object`)
  return value
}

function string(json: Json, key: string): string {
  const value = json[key]
  if (typeof value !== 'string') throw new UnexpectedResponseError(`${key} must be a string`)
  return value
}

function nullableString(json: Json, key: string): string | null {
  return json[key] == null ? null : string(json, key)
}

function number(json: Json, key: string): number {
  const value = json[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new UnexpectedResponseError(`${key} must be a number`)
  return value
}

function boolean(json: Json, key: string): boolean {
  const value = json[key]
  if (typeof value !== 'boolean') throw new UnexpectedResponseError(`${key} must be a boolean`)
  return value
}

function oneOf<T extends string>(value: string, known: readonly T[]): T | 'unknown' {
  return (known as readonly string[]).includes(value) ? (value as T) : 'unknown'
}
