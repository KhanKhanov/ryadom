// LiveKit — самая большая часть сайта (около 570 КБ). Он загружается только при первом звонке,
// поэтому вход и кабинет волонтёра открываются быстрее, особенно с медленного телефона.

import { initialCallState, type CallFactory, type CallSession, type CallState } from './call'

export const createDeferredLiveKitCall: CallFactory = (credentials, options) =>
  new DeferredCall(import('./livekitCall').then(({ createLiveKitCall }) => createLiveKitCall(credentials, options)))

/** Звонок, настоящая реализация которого ещё загружается. Вызовы выполняются по порядку, когда она готова. */
export class DeferredCall implements CallSession {
  private readonly session: Promise<CallSession>
  private failed = false

  constructor(session: Promise<CallSession>) {
    this.session = session
    // Не загрузилось (нет сети) — подписчики узнают об этом из subscribe().
    session.catch(() => {
      this.failed = true
    })
  }

  async connect(): Promise<void> {
    await (await this.session).connect()
  }

  async disconnect(): Promise<void> {
    if (this.failed) return
    await (await this.session).disconnect()
  }

  async setMicrophoneEnabled(enabled: boolean): Promise<boolean> {
    return (await this.session).setMicrophoneEnabled(enabled)
  }

  async startAudio(): Promise<void> {
    await (await this.session).startAudio()
  }

  attachVideo(kind: 'remote' | 'local', element: HTMLVideoElement | null): void {
    this.session.then((session) => session.attachVideo(kind, element)).catch(() => undefined)
  }

  subscribe(listener: (state: CallState) => void): () => void {
    let active = true
    let unsubscribe: (() => void) | null = null
    listener(initialCallState)
    this.session.then(
      (session) => {
        if (active) unsubscribe = session.subscribe(listener)
      },
      () => {
        // Показать «Соединение потеряно» и кнопку «Подключиться снова».
        if (active) listener({ ...initialCallState, connection: 'disconnected' })
      },
    )
    return () => {
      active = false
      unsubscribe?.()
    }
  }
}
