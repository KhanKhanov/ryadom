import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiClient } from '../api/client'
import { NetworkError, SessionEndedError, type SessionEndReason } from '../api/errors'
import type { UserProfile } from '../api/types'
import type { StringKey } from '../i18n'
import { useServices } from '../ui/context'
import { errorKey } from '../ui/errorText'
import type { YandexCallback } from './yandex'

export type AuthState =
  | { kind: 'loading' }
  /** Сервер недоступен — не понять, действует ли вход. */
  | { kind: 'offline' }
  /** [message] — почему пришлось войти снова (сеанс истёк, вход отменён). */
  | { kind: 'loggedOut'; message: StringKey | null }
  | { kind: 'loggedIn'; profile: UserProfile }

export type Auth = {
  state: AuthState
  /** Вход выполнен на экране входа. */
  loggedIn(profile: UserProfile): void
  /** Профиль изменился (роль, «готов помогать»). */
  profileChanged(profile: UserProfile): void
  logout(): void
  /** Ещё раз проверить вход (после «нет связи с сервером»). */
  retry(): void
}

/**
 * Состояние входа. При открытии страницы: завершает вход через Яндекс ID (если пользователь вернулся
 * от Яндекса) или проверяет сохранённый вход запросом профиля. Следит за концом сеанса —
 * в этой вкладке (истёк refresh-токен, блокировка) и в других (выход, вход под другим именем).
 */
export function useAuth(yandexCallback: YandexCallback | null): Auth {
  const { api } = useServices()
  const [state, setState] = useState<AuthState>(() => initialState(yandexCallback, api.hasSession()))
  const started = useRef(false)

  useEffect(() => {
    // Код Яндекса одноразовый: в режиме разработки React запускает эффекты дважды, второй раз не нужен.
    if (started.current) return
    started.current = true
    if (yandexCallback?.kind === 'code') {
      api.loginYandex(yandexCallback.code, yandexCallback.codeVerifier).then(
        (profile) => setState({ kind: 'loggedIn', profile }),
        (error: unknown) => setState({ kind: 'loggedOut', message: errorKey(error) }),
      )
    } else if (yandexCallback === null && api.hasSession()) {
      void loadProfile(api).then(setState)
    }
  }, [api, yandexCallback])

  useEffect(() => {
    const unsubscribe = api.onSessionEvent((event) => {
      if (event.kind === 'ended') setState({ kind: 'loggedOut', message: endMessage(event.reason) })
      else void loadProfile(api).then(setState)
    })
    const unwatch = api.watchOtherTabs(window)
    return () => {
      unsubscribe()
      unwatch()
    }
  }, [api])

  return {
    state,
    loggedIn: useCallback((profile: UserProfile) => setState({ kind: 'loggedIn', profile }), []),
    profileChanged: useCallback((profile: UserProfile) => setState({ kind: 'loggedIn', profile }), []),
    logout: useCallback(() => void api.logout(), [api]),
    retry: useCallback(() => {
      setState({ kind: 'loading' })
      void loadProfile(api).then(setState)
    }, [api]),
  }
}

/** Профиль по сохранённому входу — или почему его не получить. */
async function loadProfile(api: ApiClient): Promise<AuthState> {
  try {
    return { kind: 'loggedIn', profile: await api.getMe() }
  } catch (error) {
    if (error instanceof SessionEndedError) return { kind: 'loggedOut', message: endMessage(error.reason) }
    if (error instanceof NetworkError) return { kind: 'offline' }
    return { kind: 'loggedOut', message: errorKey(error) }
  }
}

function initialState(callback: YandexCallback | null, hasSession: boolean): AuthState {
  if (callback?.kind === 'error') {
    return { kind: 'loggedOut', message: callback.error === 'denied' ? 'errorOauthDenied' : 'errorOauthFailed' }
  }
  if (callback === null && !hasSession) return { kind: 'loggedOut', message: null }
  return { kind: 'loading' }
}

function endMessage(reason: SessionEndReason): StringKey | null {
  switch (reason) {
    case 'expired':
      return 'errorSessionExpired'
    case 'banned':
      return 'errorBanned'
    case 'logged_out':
      return null
  }
}
