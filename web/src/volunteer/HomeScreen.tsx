import { useEffect, useRef, useState, type ChangeEvent, type FormEvent, type ReactNode } from 'react'
import type { RealtimeStatus } from '../api/realtime'
import type { HelpRequest, UserProfile } from '../api/types'
import { SwitchToBlindPanel } from '../auth/RoleScreens'
import { format, type StringKey, type Strings } from '../i18n'
import { Button, ErrorMessage, ScreenHeading } from '../ui/components'
import { useAnnounce, useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { useNow } from '../ui/hooks'
import { hasQuietHours, isQuietNow } from './quietHours'
import type { NoticeKey } from './volunteerState'

type HomeScreenProps = {
  profile: UserProfile
  onProfileChange(profile: UserProfile): void
  connection: RealtimeStatus
  incoming: HelpRequest[]
  accepting: string | null
  notice: NoticeKey | null
  onAccept(requestId: string): void
  onSkip(requestId: string): void
  /** Ошибка последнего действия. */
  children?: ReactNode
}

/** Главный экран волонтёра: «Готов помогать», время тишины, входящие вызовы. */
export function HomeScreen(props: HomeScreenProps) {
  const t = useStrings()
  const { ringer } = useServices()
  useConnectionAnnouncements(props.connection)
  return (
    <section className="screen">
      <ScreenHeading>{t.volunteerTitle}</ScreenHeading>
      <ReadyToggle profile={props.profile} onProfileChange={props.onProfileChange} />
      <p className="connection">{t[connectionKey(props.connection)]}</p>
      <IncomingCalls {...props} />
      {props.children}
      <QuietHours profile={props.profile} onProfileChange={props.onProfileChange} />
      <div className="panel">
        <p>{t.soundHint}</p>
        <Button onClick={() => ringer.test()}>{t.soundTest}</Button>
      </div>
      <SwitchToBlindPanel onProfileChange={props.onProfileChange} />
    </section>
  )
}

function connectionKey(status: RealtimeStatus): StringKey {
  switch (status) {
    case 'connecting':
      return 'connectionConnecting'
    case 'connected':
      return 'connectionConnected'
    case 'reconnecting':
      return 'connectionReconnecting'
  }
}

/**
 * Без связи с сервером вызовы не приходят — экранный диктор сообщает, когда связь пропала
 * и когда вернулась. Первое подключение при открытии страницы не объявляется.
 */
function useConnectionAnnouncements(connection: RealtimeStatus) {
  const t = useStrings()
  const announce = useAnnounce()
  const lost = useRef(false)
  useEffect(() => {
    if (connection === 'reconnecting' && !lost.current) {
      lost.current = true
      announce(t.connectionReconnecting)
    } else if (connection === 'connected' && lost.current) {
      lost.current = false
      announce(t.connectionConnected)
    }
  }, [announce, connection, t])
}

/** Переключатель «Готов помогать» — поле профиля notificationsEnabled. */
function ReadyToggle({ profile, onProfileChange }: Pick<HomeScreenProps, 'profile' | 'onProfileChange'>) {
  const t = useStrings()
  const announce = useAnnounce()
  const { api } = useServices()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)

  async function toggle(event: ChangeEvent<HTMLInputElement>) {
    if (saving) return
    const ready = event.target.checked
    setSaving(true)
    setError(null)
    try {
      onProfileChange(await api.updateMe({ notificationsEnabled: ready }))
      announce(ready ? t.readyOnAnnouncement : t.readyOffAnnouncement)
    } catch (failure) {
      setError(errorKey(failure))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="panel ready">
      <label className="switch">
        <input
          type="checkbox"
          role="switch"
          checked={profile.notificationsEnabled}
          onChange={(event) => void toggle(event)}
          aria-describedby="ready-hint"
          aria-busy={saving || undefined}
        />
        <span>{t.readyLabel}</span>
      </label>
      <p id="ready-hint">{profile.notificationsEnabled ? t.readyHintOn : t.readyHintOff}</p>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
    </div>
  )
}

/**
 * Сообщения, которые показываются над списком вызовов (голосом они уже объявлены). «Собеседник завершил
 * звонок» здесь не показывается — оно на экране после звонка и к новым вызовам не относится.
 */
const INCOMING_NOTICES: ReadonlySet<NoticeKey> = new Set<NoticeKey>([
  'noticeTaken',
  'noticeAcceptedElsewhere',
  'noticeCancelled',
  'noticeNoAnswer',
  'errorRequestTaken',
  'errorRequestClosed',
  'noticeCallEnded',
  'noticeCallNotStarted',
])

function IncomingCalls({ incoming, accepting, notice, onAccept, onSkip }: HomeScreenProps) {
  const t = useStrings()
  return (
    <section className="incoming" aria-labelledby="incoming-title">
      <h3 id="incoming-title">{t.incomingTitle}</h3>
      {notice && INCOMING_NOTICES.has(notice) && <p className="notice">{t[notice]}</p>}
      {incoming.length === 0 ? (
        <p>{t.incomingNone}</p>
      ) : (
        <ul className="incoming-list">
          {incoming.map((request) => (
            <li key={request.id} className="panel incoming-card">
              <div id={`incoming-${request.id}`}>
                <p className="incoming-card-title">{t.incomingCard}</p>
                <p>{format(t.incomingLanguage, { language: languageName(request.language, t) })}</p>
              </div>
              <div className="actions">
                <Button
                  variant="primary"
                  busy={accepting === request.id}
                  disabled={accepting !== null && accepting !== request.id}
                  aria-describedby={`incoming-${request.id}`}
                  onClick={() => onAccept(request.id)}
                >
                  {accepting === request.id ? t.accepting : t.accept}
                </Button>
                <Button disabled={accepting !== null} aria-describedby={`incoming-${request.id}`} onClick={() => onSkip(request.id)}>
                  {t.skip}
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function languageName(code: string, t: Strings): string {
  if (code === 'ru') return t.languageRu
  if (code === 'en') return t.languageEn
  return t.languageUnknown
}

/** Время тишины: показ и изменение. Полночь не мешает: окно 22:00–08:00 переходит через неё. */
function QuietHours({ profile, onProfileChange }: Pick<HomeScreenProps, 'profile' | 'onProfileChange'>) {
  const t = useStrings()
  const announce = useAnnounce()
  const { api } = useServices()
  const now = useNow()
  const [editing, setEditing] = useState(false)
  const [from, setFrom] = useState(profile.doNotDisturb.from)
  const [to, setTo] = useState(profile.doNotDisturb.to)
  const [allDay, setAllDay] = useState(!hasQuietHours(profile.doNotDisturb))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)

  const dnd = profile.doNotDisturb
  const quiet = hasQuietHours(dnd)

  function startEditing() {
    setFrom(quiet ? dnd.from : '22:00')
    setTo(quiet ? dnd.to : '08:00')
    setAllDay(!quiet)
    setError(null)
    setEditing(true)
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setError(null)
    try {
      // Начало, равное концу, — окна нет (docs/api/openapi.yaml, DoNotDisturb).
      const doNotDisturb = allDay ? { from: '00:00', to: '00:00' } : { from, to }
      onProfileChange(await api.updateMe({ doNotDisturb }))
      setEditing(false)
      announce(t.saved)
    } catch (failure) {
      setError(errorKey(failure))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="panel" aria-labelledby="quiet-title">
      <h3 id="quiet-title">{t.quietTitle}</h3>
      {!editing && (
        <>
          <p>{quiet ? format(t.quietWindow, { from: dnd.from, to: dnd.to, timezone: profile.timezone }) : t.quietNone}</p>
          {quiet && isQuietNow(dnd, profile.timezone, now) && <p className="notice">{format(t.quietNow, { to: dnd.to })}</p>}
          <Button onClick={startEditing}>{t.quietEdit}</Button>
        </>
      )}
      {editing && (
        <form onSubmit={(event) => void save(event)}>
          <label className="checkbox">
            <input type="checkbox" checked={allDay} onChange={(event) => setAllDay(event.target.checked)} />
            <span>{t.quietOff}</span>
          </label>
          {!allDay && (
            <div className="time-fields">
              <div className="field">
                <label htmlFor="quiet-from">{t.quietFrom}</label>
                <input id="quiet-from" type="time" required value={from} onChange={(event) => setFrom(event.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="quiet-to">{t.quietTo}</label>
                <input id="quiet-to" type="time" required value={to} onChange={(event) => setTo(event.target.value)} />
              </div>
            </div>
          )}
          {error && <ErrorMessage>{t[error]}</ErrorMessage>}
          <div className="actions">
            <Button type="submit" variant="primary" busy={saving}>
              {t.save}
            </Button>
            <Button onClick={() => setEditing(false)}>{t.cancel}</Button>
          </div>
        </form>
      )}
    </section>
  )
}

type AfterCallScreenProps = {
  requestId: string
  endedByOther: boolean
  /** Пока волонтёр отвечает, пришёл новый вызов. */
  hasIncoming: boolean
  onDone(): void
}

/** После звонка: «Удалось помочь?». Ответ необязателен. */
export function AfterCallScreen({ requestId, endedByOther, hasIncoming, onDone }: AfterCallScreenProps) {
  const t = useStrings()
  const announce = useAnnounce()
  const { api } = useServices()
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)

  async function rate(helped: boolean) {
    if (sending) return
    setSending(true)
    setError(null)
    try {
      await api.rateRequest(requestId, helped)
      announce(t.ratingThanks)
      onDone()
    } catch (failure) {
      setError(errorKey(failure))
      setSending(false)
    }
  }

  return (
    <section className="screen">
      <ScreenHeading>{t.callEndedTitle}</ScreenHeading>
      {endedByOther && <p>{t.callEndedByOther}</p>}
      {hasIncoming && <p className="notice">{t.incomingAnnouncement}</p>}
      <fieldset className="rating">
        <legend>{t.ratingQuestion}</legend>
        <div className="actions">
          <Button variant="primary" busy={sending} onClick={() => void rate(true)}>
            {t.ratingYes}
          </Button>
          <Button busy={sending} onClick={() => void rate(false)}>
            {t.ratingNo}
          </Button>
          <Button onClick={onDone}>{t.ratingSkip}</Button>
        </div>
      </fieldset>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
    </section>
  )
}
