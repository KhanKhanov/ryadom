import type { UserProfile } from './api/types'
import { BlindAccountScreen, ChooseRoleScreen, InfoScreen } from './auth/RoleScreens'
import { LoginScreen } from './auth/LoginScreen'
import { useAuth, type Auth } from './auth/useAuth'
import type { YandexCallback } from './auth/yandex'
import { SOURCE_CODE_URL } from './config'
import { DevBlindScreen } from './dev/DevBlindScreen'
import { format, type Language } from './i18n'
import { AppProviders } from './ui/AppProviders'
import { Button, ScreenHeading } from './ui/components'
import { useServices, useStrings, type AppServices } from './ui/context'
import { VolunteerScreen } from './volunteer/VolunteerScreen'

type AppProps = {
  language: Language
  services: AppServices
  /** Ответ Яндекса, если пользователь только что вернулся со страницы входа. */
  yandexCallback?: YandexCallback | null
}

/**
 * Веб-приложение «Рядом»: кабинет волонтёра. Подвал со ссылкой на исходный код
 * должен оставаться на всех страницах (требование лицензии AGPL).
 */
export function App({ language, services, yandexCallback = null }: AppProps) {
  return (
    <AppProviders services={services} language={language}>
      <Shell yandexCallback={yandexCallback} />
    </AppProviders>
  )
}

function Shell({ yandexCallback }: { yandexCallback: YandexCallback | null }) {
  const t = useStrings()
  const auth = useAuth(yandexCallback)
  const profile = auth.state.kind === 'loggedIn' ? auth.state.profile : null
  return (
    <>
      <header className="header">
        <h1 className="app-name">{t.appName}</h1>
        {profile && (
          <div className="user">
            {profile.displayName && <span>{format(t.signedInAs, { name: profile.displayName })}</span>}
            <Button onClick={auth.logout}>{t.logout}</Button>
          </div>
        )}
      </header>
      <main className="main">
        <Content auth={auth} />
      </main>
      <footer className="footer">
        <a href={SOURCE_CODE_URL}>{t.sourceCode}</a>
      </footer>
    </>
  )
}

function Content({ auth }: { auth: Auth }) {
  const t = useStrings()
  switch (auth.state.kind) {
    case 'loading':
      return <p>{t.loading}</p>
    case 'offline':
      return (
        <section className="screen">
          <ScreenHeading>{t.offlineTitle}</ScreenHeading>
          <p>{t.errorNetwork}</p>
          <Button variant="primary" onClick={auth.retry}>
            {t.retry}
          </Button>
        </section>
      )
    case 'loggedOut':
      // key: новое сообщение (например, «сеанс истёк») — экран входа начинается заново и показывает его.
      return <LoginScreen key={auth.state.message ?? ''} message={auth.state.message} onLoggedIn={auth.loggedIn} />
    case 'loggedIn':
      // key: другой пользователь — экраны начинаются заново, без чужого состояния.
      return <RoleContent key={auth.state.profile.id} profile={auth.state.profile} onProfileChange={auth.profileChanged} />
  }
}

function RoleContent({ profile, onProfileChange }: { profile: UserProfile; onProfileChange(profile: UserProfile): void }) {
  const { config } = useServices()
  switch (profile.role) {
    case 'volunteer':
      return <VolunteerScreen profile={profile} onProfileChange={onProfileChange} />
    case null:
      return <ChooseRoleScreen onProfileChange={onProfileChange} />
    case 'blind':
      return config.devTools ? <DevBlindScreen /> : <BlindAccountScreen onProfileChange={onProfileChange} />
    case 'admin':
      return <InfoScreen title="roleAdminTitle" text="roleAdminText" />
    default:
      return <InfoScreen title="roleUnknownTitle" text="roleUnknownText" />
  }
}
