import { useState, type FormEvent } from 'react'
import type { UserProfile } from '../api/types'
import type { StringKey } from '../i18n'
import { Button, ErrorMessage, ScreenHeading } from '../ui/components'
import { useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { startYandexLogin } from './yandex'

/** Допустимый логин для входа без OAuth — как на сервере (DevLoginRequest.LOGIN_REGEX). */
const DEV_LOGIN_PATTERN = /^[a-z0-9_-]{1,32}$/

type LoginScreenProps = {
  /** Почему пришлось войти снова. */
  message: StringKey | null
  onLoggedIn(profile: UserProfile): void
}

export function LoginScreen({ message, onLoggedIn }: LoginScreenProps) {
  const t = useStrings()
  const { api, config, tabStorage, navigate } = useServices()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<StringKey | null>(message)
  const [login, setLogin] = useState('')

  async function loginWithYandex(clientId: string) {
    setBusy(true)
    setError(null)
    try {
      navigate(await startYandexLogin(clientId, config.yandexRedirectUri, tabStorage))
    } catch {
      setError('errorGeneric')
      setBusy(false)
    }
  }

  async function loginWithoutOAuth(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const value = login.trim()
    if (!DEV_LOGIN_PATTERN.test(value)) {
      setError('errorInvalidLogin')
      return
    }
    setBusy(true)
    setError(null)
    try {
      onLoggedIn(await api.loginDev(value))
    } catch (failure) {
      setError(errorKey(failure, { not_found: 'errorLoginNotFound', invalid_request: 'errorInvalidLogin' }))
      setBusy(false)
    }
  }

  const yandexClientId = config.yandexClientId
  return (
    <section className="screen">
      <ScreenHeading>{t.loginTitle}</ScreenHeading>
      <p>{t.loginIntro}</p>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
      {yandexClientId && (
        <Button variant="primary" busy={busy} onClick={() => void loginWithYandex(yandexClientId)}>
          {busy ? t.loggingIn : t.loginWithYandex}
        </Button>
      )}
      {!yandexClientId && !config.devTools && <p>{t.loginUnavailable}</p>}
      {config.devTools && (
        <form className="panel dev-panel" onSubmit={(event) => void loginWithoutOAuth(event)} aria-labelledby="dev-login-title" noValidate>
          <h3 id="dev-login-title">
            {t.devLoginTitle} <span className="badge">{t.devOnly}</span>
          </h3>
          <div className="field">
            <label htmlFor="dev-login">{t.devLoginLabel}</label>
            <input
              id="dev-login"
              name="login"
              value={login}
              onChange={(event) => setLogin(event.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              aria-describedby="dev-login-hint"
            />
            <p id="dev-login-hint" className="hint">
              {t.devLoginHint}
            </p>
          </div>
          <Button type="submit" busy={busy}>
            {busy ? t.loggingIn : t.devLoginSubmit}
          </Button>
        </form>
      )}
    </section>
  )
}
