import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { ErrorCodes, SessionEndedError, errorCode } from '../api/errors'
import { isCallActive, type UserProfile } from '../api/types'
import { CallScreen } from '../call/CallScreen'
import type { StringKey } from '../i18n'
import { useWebPush } from '../push/useWebPush'
import { ErrorMessage } from '../ui/components'
import { useAnnounce, useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { useLatest, useRealtime } from '../ui/hooks'
import { AfterCallScreen, HomeScreen } from './HomeScreen'
import { initialVolunteerState, volunteerReducer } from './volunteerState'

type VolunteerScreenProps = {
  profile: UserProfile
  onProfileChange(profile: UserProfile): void
}

/**
 * Кабинет волонтёра: держит соединение событий, принимает вызовы и показывает звонок.
 * Логика смены состояний — в volunteerState.ts, здесь — сеть, звук и экраны.
 */
export function VolunteerScreen({ profile, onProfileChange }: VolunteerScreenProps) {
  const t = useStrings()
  const announce = useAnnounce()
  const { api, ringer, push } = useServices()
  const [state, dispatch] = useReducer(volunteerReducer, initialVolunteerState)
  const [error, setError] = useState<StringKey | null>(null)
  const latest = useLatest(state)
  const webPush = useWebPush()

  /**
   * События, случившиеся без связи, сервер не повторяет — после каждого подключения (и когда волонтёр
   * нажал на уведомление) перечитываем идущий звонок и вызовы, которые ждут ответа.
   */
  async function resync() {
    try {
      const current = await api.getCurrentRequest()
      if (current?.call && isCallActive(current.status)) {
        dispatch({ type: 'callRestored', request: current })
      } else {
        const call = latest.current.call
        if (call) dispatch({ type: 'requestUpdated', request: await api.getRequest(call.id) })
      }
      const incoming = await api.getIncomingRequests()
      dispatch({ type: 'incomingSynced', requests: incoming })
      // Уведомления о вызовах, которые уже не ждут ответа или пропущены здесь, больше не нужны.
      const waiting = new Set(incoming.map((request) => request.id))
      const skipped = latest.current.skipped
      push.closeNotifications((requestId) => !waiting.has(requestId) || skipped.includes(requestId)).catch(() => undefined)
    } catch (failure) {
      if (failure instanceof SessionEndedError) return
      // Нет связи: следующая попытка — при следующем подключении WebSocket.
    }
  }
  const latestResync = useLatest(resync)

  const connection = useRealtime({
    onReady: () => void resync(),
    onEvent: (event) => dispatch({ type: 'event', event }),
  })

  // Нажали на уведомление о вызове — вкладка уже открыта, но связь могла пропасть, пока она была в фоне.
  useEffect(() => push.onNotificationClick(() => void latestResync.current()), [push, latestResync])

  // Вызов исчез со страницы (принят, отменён, пропущен, начался звонок) — уведомление о нём тоже убрать.
  const shownIncoming = useRef<string[]>([])
  useEffect(() => {
    const now = state.incoming.map((request) => request.id)
    const removed = shownIncoming.current.filter((id) => !now.includes(id))
    shownIncoming.current = now
    if (removed.length > 0) push.closeNotifications((requestId) => removed.includes(requestId)).catch(() => undefined)
  }, [push, state.incoming])

  // Сообщения о вызовах звучат голосом; входящий вызов — срочно, с перебиванием диктора.
  useEffect(() => {
    if (!state.notice) return
    announce(t[state.notice.key], state.notice.key === 'incomingAnnouncement' ? 'assertive' : 'polite')
  }, [announce, state.notice, t])

  // Пока есть входящий вызов — звук и заметный заголовок вкладки (вкладка может быть в фоне).
  const ringing = state.incoming.length > 0 && !state.call
  useEffect(() => {
    if (!ringing) return
    ringer.start()
    document.title = t.incomingTabTitle
    return () => {
      ringer.stop()
      document.title = t.appName
    }
  }, [ringing, ringer, t])

  /**
   * Волонтёр завершает звонок. Сервер шлёт request.ended обоим участникам, и событие может прийти
   * раньше ответа — заранее отмечаем, что звонок завершает сам волонтёр, а не собеседник.
   */
  const endCall = useCallback(
    async (requestId: string) => {
      dispatch({ type: 'endStarted', requestId })
      try {
        dispatch({ type: 'callEnded', request: await api.cancelRequest(requestId) })
      } catch (failure) {
        dispatch({ type: 'endFailed', requestId })
        throw failure
      }
    },
    [api],
  )

  // Выход во время звонка сначала завершает его: иначе звонок остался бы открытым на сервере,
  // незрячий ждал бы ушедшего волонтёра, а сам волонтёр не получал бы новых вызовов.
  const callId = state.call?.id ?? null
  useEffect(() => {
    if (!callId) return
    return api.onBeforeLogout(() => endCall(callId))
  }, [api, callId, endCall])

  async function accept(requestId: string) {
    if (latest.current.accepting) return
    setError(null)
    dispatch({ type: 'acceptStarted', requestId })
    try {
      const request = await api.acceptRequest(requestId)
      if (request.call && isCallActive(request.status)) dispatch({ type: 'acceptSucceeded', request })
      else dispatch({ type: 'acceptFailed', requestId, reason: 'closed' })
    } catch (failure) {
      switch (errorCode(failure)) {
        case ErrorCodes.requestTaken:
          dispatch({ type: 'acceptFailed', requestId, reason: 'taken' })
          break
        case ErrorCodes.requestClosed:
        case ErrorCodes.notFound:
          dispatch({ type: 'acceptFailed', requestId, reason: 'closed' })
          break
        case ErrorCodes.activeRequestExists:
          // Звонок уже идёт — например, принят в другой вкладке. Покажем его.
          dispatch({ type: 'acceptFailed', requestId, reason: 'retry' })
          setError('errorAlreadyInCall')
          void resync()
          break
        default:
          dispatch({ type: 'acceptFailed', requestId, reason: 'retry' })
          if (!(failure instanceof SessionEndedError)) setError(errorKey(failure))
      }
    }
  }

  if (state.call?.call) {
    const requestId = state.call.id
    return (
      <CallScreen
        key={requestId}
        requestId={requestId}
        credentials={state.call.call}
        mode="volunteer"
        onEnd={() => endCall(requestId)}
        onClosed={(request) => dispatch({ type: 'requestUpdated', request })}
        onRemoteJoined={() => dispatch({ type: 'remoteJoined', requestId })}
      />
    )
  }

  if (state.finished) {
    return (
      <AfterCallScreen
        requestId={state.finished.requestId}
        endedByOther={state.finished.byOther}
        hasIncoming={state.incoming.length > 0}
        onDone={() => dispatch({ type: 'ratingDone' })}
      />
    )
  }

  return (
    <HomeScreen
      profile={profile}
      onProfileChange={onProfileChange}
      connection={connection}
      push={webPush}
      incoming={state.incoming}
      accepting={state.accepting}
      notice={state.notice?.key ?? null}
      onAccept={(requestId) => void accept(requestId)}
      onSkip={(requestId) => dispatch({ type: 'skip', requestId })}
    >
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}
    </HomeScreen>
  )
}
