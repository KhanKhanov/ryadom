import { useEffect, useRef, useState } from 'react'
import { isCallActive, type CallCredentials, type HelpRequest } from '../api/types'
import { format, type StringKey } from '../i18n'
import { Button, ErrorMessage, ScreenHeading } from '../ui/components'
import { useAnnounce, useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'
import { useLatest } from '../ui/hooks'
import { callStatusKey, initialCallState, type CallSession, type CallState } from './call'

type CallScreenProps = {
  requestId: string
  credentials: CallCredentials
  /**
   * `volunteer` — видно камеру собеседника, своя камера не публикуется;
   * `blind` (тестовый незрячий) — публикуется камера этого компьютера и видно её превью.
   */
  mode: 'volunteer' | 'blind'
  /** Пользователь нажал «Завершить звонок»: закрыть запрос на сервере. */
  onEnd(): Promise<void>
  /** Звонок уже закрыт на сервере (выяснилось при попытке переподключиться). */
  onClosed(request: HelpRequest): void
  /** Собеседник появился в звонке — разговор состоялся. */
  onRemoteJoined?(): void
}

/**
 * Экран звонка. Пока он показан, вкладка подключена к комнате LiveKit; при уходе с экрана —
 * отключается. О завершении звонка собеседником сообщает сервер (событие request.ended),
 * и родительский экран убирает этот.
 */
export function CallScreen({ requestId, credentials: initialCredentials, mode, onEnd, onClosed, onRemoteJoined }: CallScreenProps) {
  const t = useStrings()
  const announce = useAnnounce()
  const { api, createCall } = useServices()
  const [credentials, setCredentials] = useState(initialCredentials)
  const [call, setCall] = useState<CallState>(initialCallState)
  const [ending, setEnding] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)
  const session = useRef<CallSession | null>(null)
  const remoteVideo = useRef<HTMLVideoElement>(null)
  const localVideo = useRef<HTMLVideoElement>(null)
  const latestOnRemoteJoined = useLatest(onRemoteJoined)

  // Новые данные для входа (после «Подключиться снова») — новое подключение.
  useEffect(() => {
    const current = createCall(credentials, { publishCamera: mode === 'blind' })
    session.current = current
    const unsubscribe = current.subscribe(setCall)
    current.attachVideo('remote', remoteVideo.current)
    current.attachVideo('local', localVideo.current)
    void current.connect()
    return () => {
      unsubscribe()
      session.current = null
      void current.disconnect()
    }
  }, [createCall, credentials, mode])

  // Изменения состояния звонка объявляются голосом, а не только показываются.
  const status = callStatusKey(call)
  useEffect(() => {
    announce(t[status])
  }, [announce, status, t])

  const remotePresent = call.remote === 'present'
  useEffect(() => {
    if (remotePresent) latestOnRemoteJoined.current?.()
  }, [latestOnRemoteJoined, remotePresent])

  // Случайно закрытая вкладка оборвала бы звонок — браузер переспросит.
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  async function toggleMicrophone() {
    const current = session.current
    if (!current) return
    const enable = call.microphone !== 'on'
    // Объявляем то, что получилось, а не то, что пользователь хотел.
    const done = await current.setMicrophoneEnabled(enable)
    announce(!done ? t.micBlocked : enable ? t.micOnAnnouncement : t.micMutedAnnouncement)
  }

  async function end() {
    if (ending) return
    setEnding(true)
    setError(null)
    try {
      await onEnd()
    } catch (failure) {
      setError(errorKey(failure))
      setEnding(false)
    }
  }

  async function reconnect() {
    setError(null)
    try {
      const request = await api.getRequest(requestId)
      if (request.call && isCallActive(request.status)) setCredentials(request.call)
      else onClosed(request)
    } catch (failure) {
      setError(errorKey(failure))
    }
  }

  return (
    <section className="screen call">
      <ScreenHeading>{t.callTitle}</ScreenHeading>
      <p className="call-status">{t[status]}</p>
      {call.remoteName && call.remote === 'present' && <p>{format(t.callWith, { name: call.remoteName })}</p>}

      {mode === 'volunteer' ? (
        <div className="video-frame">
          <video ref={remoteVideo} autoPlay playsInline muted aria-label={t.remoteVideoLabel} />
          {!call.remoteVideo && <p className="video-placeholder">{t.noVideo}</p>}
        </div>
      ) : (
        <div className="video-frame video-frame-small">
          <video ref={localVideo} autoPlay playsInline muted aria-label={t.localVideoLabel} />
          {call.camera !== 'on' && <p className="video-placeholder">{t.noVideo}</p>}
        </div>
      )}

      {call.microphone === 'blocked' && <ErrorMessage>{t.micBlocked}</ErrorMessage>}
      {call.camera === 'blocked' && <ErrorMessage>{t.cameraBlocked}</ErrorMessage>}
      {error && <ErrorMessage>{t[error]}</ErrorMessage>}

      <div className="actions">
        {call.audioBlocked && (
          <div className="inline-notice">
            <p>{t.audioBlocked}</p>
            <Button variant="primary" onClick={() => void session.current?.startAudio()}>
              {t.enableAudio}
            </Button>
          </div>
        )}
        {call.connection === 'disconnected' && (
          <Button variant="primary" onClick={() => void reconnect()}>
            {t.callReconnect}
          </Button>
        )}
        {call.connection === 'connected' && call.microphone !== 'starting' && (
          <Button onClick={() => void toggleMicrophone()}>{call.microphone === 'on' ? t.micMute : t.micUnmute}</Button>
        )}
        <Button variant="danger" busy={ending} onClick={() => void end()}>
          {ending ? t.ending : t.endCall}
        </Button>
      </div>
    </section>
  )
}
