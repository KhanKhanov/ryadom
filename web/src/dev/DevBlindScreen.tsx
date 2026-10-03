// «Тестовый незрячий» — только для разработки (config.devTools): создаёт запрос помощи и показывает
// камеру компьютера, чтобы проверить звонок волонтёра без Android-приложения.
// Настоящего интерфейса для незрячих в веб-версии нет (docs/ARCHITECTURE.md, раздел 8).

import { useCallback, useEffect, useState } from 'react'
import { ErrorCodes, SessionEndedError, errorCode } from '../api/errors'
import { isActive, isCallActive, type CallCredentials, type HelpRequest } from '../api/types'
import { CallScreen } from '../call/CallScreen'
import type { StringKey } from '../i18n'
import { Button, ErrorMessage, ScreenHeading } from '../ui/components'
import { useAnnounce, useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { useLatest, useRealtime } from '../ui/hooks'

type BlindState =
  | { kind: 'idle'; message: StringKey | null }
  | { kind: 'searching'; request: HelpRequest }
  | { kind: 'call'; request: HelpRequest; credentials: CallCredentials }

/** Новое состояние по свежим данным о запросе [request]. Запросы, кроме текущего, не трогаем. */
function nextState(current: BlindState, request: HelpRequest): BlindState {
  if (current.kind !== 'idle' && current.request.id !== request.id) return current
  if (request.status === 'searching') return { kind: 'searching', request }
  if (isCallActive(request.status) && request.call) {
    // В идущем звонке данные для входа не меняем — иначе экран звонка переподключится.
    return current.kind === 'call' ? { ...current, request } : { kind: 'call', request, credentials: request.call }
  }
  if (isActive(request.status)) return current
  const message: StringKey | null =
    request.status === 'no_answer'
      ? 'noAnswer'
      : request.status === 'cancelled'
        ? 'searchCancelled'
        : request.status === 'ended'
          ? 'callEndedTitle'
          : null
  return { kind: 'idle', message }
}

export function DevBlindScreen() {
  const t = useStrings()
  const announce = useAnnounce()
  const { api } = useServices()
  const [state, setState] = useState<BlindState>({ kind: 'idle', message: null })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)
  const latest = useLatest(state)

  const apply = useCallback((request: HelpRequest) => setState((current) => nextState(current, request)), [])

  const resync = useCallback(async () => {
    try {
      const current = await api.getCurrentRequest()
      const tracked = latest.current
      if (current) apply(current)
      else if (tracked.kind !== 'idle') apply(await api.getRequest(tracked.request.id))
    } catch {
      // Нет связи — перечитаем при следующем подключении.
    }
  }, [api, apply, latest])

  useRealtime({ onReady: () => void resync(), onEvent: (event) => apply(event.request) })

  // Каждая смена состояния объявляется голосом.
  const spoken: StringKey | null =
    state.kind === 'searching' ? 'searching' : state.kind === 'call' ? 'volunteerFound' : state.message
  useEffect(() => {
    if (spoken) announce(t[spoken])
  }, [announce, spoken, t])

  async function run(action: () => Promise<HelpRequest>) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      apply(await action())
    } catch (failure) {
      if (failure instanceof SessionEndedError) return
      // Активный запрос уже есть (например, из другой вкладки) — покажем его.
      if (errorCode(failure) === ErrorCodes.activeRequestExists) void resync()
      else setError(errorKey(failure))
    } finally {
      setBusy(false)
    }
  }

  if (state.kind === 'call') {
    const requestId = state.request.id
    return (
      <CallScreen
        key={requestId}
        requestId={requestId}
        credentials={state.credentials}
        mode="blind"
        onEnd={async () => apply(await api.cancelRequest(requestId))}
        onClosed={apply}
      />
    )
  }

  return (
    <section className="screen">
      <ScreenHeading>{t.devBlindTitle}</ScreenHeading>
      <p>
        <span className="badge">{t.devOnly}</span> {t.devBlindIntro}
      </p>
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
      {state.kind === 'searching' ? (
        <>
          <p className="call-status">{t.searching}</p>
          <Button busy={busy} onClick={() => void run(() => api.cancelRequest(state.request.id))}>
            {t.cancelRequest}
          </Button>
        </>
      ) : (
        <>
          {state.message && <p className="notice">{t[state.message]}</p>}
          <Button variant="primary" className="button-large" busy={busy} onClick={() => void run(() => api.createRequest())}>
            {t.requestHelp}
          </Button>
        </>
      )}
    </section>
  )
}
