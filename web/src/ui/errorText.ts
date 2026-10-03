import { ApiError, ErrorCodes, NetworkError } from '../api/errors'
import type { StringKey } from '../i18n'

/**
 * Какой текст показать пользователю при ошибке. [overrides] — свои тексты для кодов,
 * которые на этом экране значат что-то особое. Неизвестный код — общее сообщение:
 * сервер может добавить новые коды (docs/ARCHITECTURE.md, раздел 6).
 */
export function errorKey(error: unknown, overrides: Partial<Record<string, StringKey>> = {}): StringKey {
  if (error instanceof NetworkError) return 'errorNetwork'
  if (!(error instanceof ApiError)) return 'errorGeneric'
  const own = overrides[error.code]
  if (own) return own
  switch (error.code) {
    case ErrorCodes.userBanned:
      return 'errorBanned'
    case ErrorCodes.oauthFailed:
      return 'errorOauthFailed'
    case ErrorCodes.providerUnavailable:
      return 'errorProviderUnavailable'
    case ErrorCodes.requestTaken:
      return 'errorRequestTaken'
    case ErrorCodes.requestClosed:
      return 'errorRequestClosed'
    case ErrorCodes.tooManyRequests:
      return 'errorTooManyRequests'
    default:
      return 'errorGeneric'
  }
}
