// Звонок глазами интерфейса. Экраны работают с этим интерфейсом, а не с LiveKit напрямую:
// так их можно тестировать с поддельным звонком (в jsdom нет WebRTC).
// Настоящая реализация — livekitCall.ts.

import type { CallCredentials } from '../api/types'
import type { StringKey } from '../i18n'

export type CallState = {
  /** Соединение с сервером видеозвонков. */
  connection: 'connecting' | 'connected' | 'reconnecting' | 'disconnected'
  /** Звонок открыли в другой вкладке тем же пользователем — LiveKit отключил эту. */
  replaced: boolean
  /** Собеседник: ещё не подключался, в звонке, ушёл. */
  remote: 'waiting' | 'present' | 'left'
  /** Имя собеседника, если он его указал. */
  remoteName: string | null
  /** Есть видео собеседника (камера включена). */
  remoteVideo: boolean
  /** Свой микрофон. `blocked` — браузер не дал доступа. */
  microphone: 'starting' | 'on' | 'muted' | 'blocked'
  /** Своя камера (только у незрячего). */
  camera: 'off' | 'starting' | 'on' | 'blocked'
  /** Браузер не дал воспроизвести звук без нажатия на странице — нужна кнопка «Включить звук». */
  audioBlocked: boolean
}

export const initialCallState: CallState = {
  connection: 'connecting',
  replaced: false,
  remote: 'waiting',
  remoteName: null,
  remoteVideo: false,
  microphone: 'starting',
  camera: 'off',
  audioBlocked: false,
}

export type CallOptions = {
  /** Публиковать камеру. У волонтёра камеры нет: токен LiveKit разрешает ему только микрофон. */
  publishCamera: boolean
}

export interface CallSession {
  /** Подключается к комнате и включает микрофон (и камеру, если нужна). */
  connect(): Promise<void>
  disconnect(): Promise<void>
  /** `false` — не получилось (браузер не дал доступа к микрофону). */
  setMicrophoneEnabled(enabled: boolean): Promise<boolean>
  /** Включает звук собеседника после нажатия пользователя, если браузер его заблокировал. */
  startAudio(): Promise<void>
  /** Куда показывать видео: собеседника (`remote`) или своей камеры (`local`). `null` — элемент убран. */
  attachVideo(kind: 'remote' | 'local', element: HTMLVideoElement | null): void
  subscribe(listener: (state: CallState) => void): () => void
}

export type CallFactory = (credentials: CallCredentials, options: CallOptions) => CallSession

/** Главное о звонке одной фразой — для строки состояния и экранного диктора. */
export function callStatusKey(call: CallState): StringKey {
  switch (call.connection) {
    case 'connecting':
      return 'callConnecting'
    case 'reconnecting':
      return 'callReconnecting'
    case 'disconnected':
      return call.replaced ? 'callReplaced' : 'callDisconnected'
    case 'connected':
      if (call.remote === 'waiting') return 'callWaiting'
      if (call.remote === 'left') return 'callRemoteLeft'
      return 'callActive'
  }
}
