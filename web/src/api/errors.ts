// Ошибки обращения к серверу.

/**
 * Коды ошибок API (схема `Error` в docs/api/openapi.yaml), которые клиент различает.
 * Сервер может добавить новые коды — для них клиент показывает общее сообщение.
 */
export const ErrorCodes = {
  invalidRequest: 'invalid_request',
  unauthorized: 'unauthorized',
  invalidRefreshToken: 'invalid_refresh_token',
  oauthFailed: 'oauth_failed',
  userBanned: 'user_banned',
  forbidden: 'forbidden',
  notFound: 'not_found',
  providerUnavailable: 'provider_unavailable',
  activeRequestExists: 'active_request_exists',
  requestTaken: 'request_taken',
  requestClosed: 'request_closed',
  callNotStarted: 'call_not_started',
  tooManyRequests: 'too_many_requests',
} as const

/** Сервер ответил ошибкой. [code] — машинный код из ответа, по нему выбирается текст для пользователя. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/** До сервера не достучаться: нет сети, сервер выключен. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super('Network request failed', { cause })
    this.name = 'NetworkError'
  }
}

/**
 * Почему закончился сеанс:
 * - `expired` — refresh-токен истёк или отозван, нужно войти снова;
 * - `banned` — пользователь заблокирован;
 * - `logged_out` — пользователь вышел (в этой или другой вкладке).
 */
export type SessionEndReason = 'expired' | 'banned' | 'logged_out'

/** Сеанса больше нет: токены удалены, нужно войти снова. */
export class SessionEndedError extends Error {
  readonly reason: SessionEndReason

  constructor(reason: SessionEndReason) {
    super(`Session ended: ${reason}`)
    this.name = 'SessionEndedError'
    this.reason = reason
  }
}

/** Код ошибки API, если это она. */
export function errorCode(error: unknown): string | null {
  return error instanceof ApiError ? error.code : null
}
