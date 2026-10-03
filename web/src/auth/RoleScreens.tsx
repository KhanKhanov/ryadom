// Экраны для пользователей, которые ещё не волонтёры: выбор роли, аккаунт незрячего, другие роли.

import { useState } from 'react'
import { ApiError, ErrorCodes } from '../api/errors'
import type { SelectableRole, UserProfile } from '../api/types'
import type { StringKey } from '../i18n'
import { Button, ErrorMessage, ScreenHeading } from '../ui/components'
import { useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { browserTimezone } from '../volunteer/quietHours'

type ProfileProps = {
  onProfileChange(profile: UserProfile): void
}

/** Роль ещё не выбрана (первый вход). */
export function ChooseRoleScreen({ onProfileChange }: ProfileProps) {
  const t = useStrings()
  const { config } = useServices()
  const { choose, busy, error } = useRoleChange(onProfileChange)
  return (
    <section className="screen">
      <ScreenHeading>{t.roleTitle}</ScreenHeading>
      <p>{t.roleIntro}</p>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
      <Button variant="primary" busy={busy} onClick={() => void choose('volunteer')}>
        {t.becomeVolunteer}
      </Button>
      {config.devTools && (
        <div className="panel dev-panel">
          <p>
            <span className="badge">{t.devOnly}</span> {t.devBlindHint}
          </p>
          <Button busy={busy} onClick={() => void choose('blind')}>
            {t.devBecomeBlind}
          </Button>
        </div>
      )}
    </section>
  )
}

/** Вошёл незрячий: веб-версия не для него, но роль можно сменить. */
export function BlindAccountScreen({ onProfileChange }: ProfileProps) {
  const t = useStrings()
  const { choose, busy, error } = useRoleChange(onProfileChange)
  return (
    <section className="screen">
      <ScreenHeading>{t.roleBlindTitle}</ScreenHeading>
      <p>{t.roleBlindText}</p>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
      <Button busy={busy} onClick={() => void choose('volunteer')}>
        {t.becomeVolunteer}
      </Button>
    </section>
  )
}

/** Роль, для которой в веб-версии пока ничего нет (администратор, неизвестная роль). */
export function InfoScreen({ title, text }: { title: StringKey; text: StringKey }) {
  const t = useStrings()
  return (
    <section className="screen">
      <ScreenHeading>{t[title]}</ScreenHeading>
      <p>{t[text]}</p>
    </section>
  )
}

/**
 * Смена роли. Вместе с ролью волонтёра сохраняется часовой пояс браузера: по нему сервер
 * считает время тишины, а пояс по умолчанию (Москва) подходит не всем.
 */
function useRoleChange(onProfileChange: (profile: UserProfile) => void) {
  const { api } = useServices()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)

  async function choose(role: SelectableRole) {
    if (busy) return
    setBusy(true)
    setError(null)
    const timezone = browserTimezone()
    try {
      let profile: UserProfile
      try {
        profile = await api.updateMe(timezone ? { role, timezone } : { role })
      } catch (failure) {
        // Сервер не знает пояс, который сообщил браузер, — сохраняем роль без него.
        if (!(timezone && failure instanceof ApiError && failure.code === ErrorCodes.invalidRequest)) throw failure
        profile = await api.updateMe({ role })
      }
      onProfileChange(profile)
    } catch (failure) {
      setError(errorKey(failure, { [ErrorCodes.activeRequestExists]: 'errorActiveRequestRole' }))
      setBusy(false)
    }
  }

  return { choose, busy, error }
}
