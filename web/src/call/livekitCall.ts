// Звонок через LiveKit (https://docs.livekit.io/reference/client-sdk-js/).
// Комната звонка называется по id запроса; в ней двое: незрячий (камера и микрофон)
// и волонтёр (только микрофон — так разрешает его токен).

import {
  ConnectionState,
  DisconnectReason,
  Room,
  RoomEvent,
  Track,
  type RemoteParticipant,
  type RemoteTrack,
} from 'livekit-client'
import type { CallCredentials } from '../api/types'
import { initialCallState, type CallFactory, type CallOptions, type CallSession, type CallState } from './call'

export const createLiveKitCall: CallFactory = (credentials, options) => new LiveKitCall(credentials, options)

class LiveKitCall implements CallSession {
  private readonly credentials: CallCredentials
  private readonly options: CallOptions
  private readonly room: Room
  private state: CallState
  private readonly listeners = new Set<(state: CallState) => void>()
  private readonly videoElements: Record<'remote' | 'local', HTMLVideoElement | null> = { remote: null, local: null }
  /** Элементы <audio> со звуком собеседника: LiveKit создаёт их, а мы добавляем на страницу и убираем. */
  private readonly audioElements = new Set<HTMLMediaElement>()
  private disconnecting = false

  constructor(credentials: CallCredentials, options: CallOptions) {
    this.credentials = credentials
    this.options = options
    this.state = { ...initialCallState, camera: options.publishCamera ? 'starting' : 'off' }
    this.room = new Room({
      // Качество видео подстраивается под размер окна и канал связи.
      adaptiveStream: true,
      dynacast: true,
      // Незрячий показывает то, что перед ним, — задней камерой телефона (если она есть).
      videoCaptureDefaults: { facingMode: 'environment' },
    })
    this.room
      .on(RoomEvent.ConnectionStateChanged, (state) => this.update({ connection: connectionOf(state) }))
      .on(RoomEvent.Disconnected, (reason) =>
        this.update({ connection: 'disconnected', replaced: reason === DisconnectReason.DUPLICATE_IDENTITY }),
      )
      .on(RoomEvent.ParticipantConnected, () => this.updateRemote())
      .on(RoomEvent.ParticipantDisconnected, () => this.updateRemote())
      .on(RoomEvent.TrackSubscribed, (track) => this.onTrackSubscribed(track))
      .on(RoomEvent.TrackUnsubscribed, (track) => this.onTrackUnsubscribed(track))
      .on(RoomEvent.TrackMuted, () => this.updateRemote())
      .on(RoomEvent.TrackUnmuted, () => this.updateRemote())
      .on(RoomEvent.AudioPlaybackStatusChanged, () => this.update({ audioBlocked: !this.room.canPlaybackAudio }))
  }

  async connect(): Promise<void> {
    try {
      await this.room.connect(this.credentials.url, this.credentials.token)
    } catch {
      // Сервер звонков недоступен или токен не подошёл. Отмена при выходе с экрана — не ошибка.
      if (!this.disconnecting) this.update({ connection: 'disconnected' })
      return
    }
    // Собеседник мог войти в комнату раньше нас.
    this.updateRemote()
    this.update({ audioBlocked: !this.room.canPlaybackAudio })
    await this.setMicrophoneEnabled(true)
    if (this.options.publishCamera) await this.enableCamera()
  }

  async disconnect(): Promise<void> {
    this.disconnecting = true
    for (const kind of ['remote', 'local'] as const) this.attachVideo(kind, null)
    await this.room.disconnect()
    for (const element of this.audioElements) element.remove()
    this.audioElements.clear()
  }

  async setMicrophoneEnabled(enabled: boolean): Promise<boolean> {
    try {
      await this.room.localParticipant.setMicrophoneEnabled(enabled)
      this.update({ microphone: enabled ? 'on' : 'muted' })
      return true
    } catch {
      // Чаще всего пользователь запретил доступ к микрофону или микрофона нет.
      if (enabled && !this.disconnecting) this.update({ microphone: 'blocked' })
      return false
    }
  }

  async startAudio(): Promise<void> {
    await this.room.startAudio()
    this.update({ audioBlocked: !this.room.canPlaybackAudio })
  }

  attachVideo(kind: 'remote' | 'local', element: HTMLVideoElement | null): void {
    const previous = this.videoElements[kind]
    if (previous === element) return
    this.videoElements[kind] = element
    const track = kind === 'remote' ? this.remoteCamera()?.track : this.room.localParticipant.getTrackPublication(Track.Source.Camera)?.track
    if (!track) return
    if (previous) track.detach(previous)
    if (element) track.attach(element)
  }

  subscribe(listener: (state: CallState) => void): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => this.listeners.delete(listener)
  }

  private async enableCamera() {
    this.update({ camera: 'starting' })
    try {
      const publication = await this.room.localParticipant.setCameraEnabled(true)
      this.update({ camera: 'on' })
      const element = this.videoElements.local
      if (element && publication?.track) publication.track.attach(element)
    } catch {
      if (!this.disconnecting) this.update({ camera: 'blocked' })
    }
  }

  private onTrackSubscribed(track: RemoteTrack) {
    if (track.kind === Track.Kind.Video) {
      const element = this.videoElements.remote
      if (element) track.attach(element)
    } else if (track.kind === Track.Kind.Audio) {
      const element = track.attach()
      document.body.append(element)
      this.audioElements.add(element)
    }
    this.updateRemote()
  }

  private onTrackUnsubscribed(track: RemoteTrack) {
    for (const element of track.detach()) {
      if (this.audioElements.delete(element)) element.remove()
    }
    this.updateRemote()
  }

  /** Собеседник — единственный другой участник комнаты. */
  private remoteParticipant(): RemoteParticipant | undefined {
    return this.room.remoteParticipants.values().next().value
  }

  private remoteCamera() {
    return this.remoteParticipant()?.getTrackPublication(Track.Source.Camera)
  }

  private updateRemote() {
    const participant = this.remoteParticipant()
    const camera = this.remoteCamera()
    this.update({
      remote: participant ? 'present' : this.state.remote === 'waiting' ? 'waiting' : 'left',
      remoteName: participant?.name || null,
      remoteVideo: Boolean(camera?.track && !camera.isMuted),
    })
  }

  private update(patch: Partial<CallState>) {
    const changed = (Object.keys(patch) as (keyof CallState)[]).some((key) => patch[key] !== this.state[key])
    if (!changed) return
    this.state = { ...this.state, ...patch }
    for (const listener of [...this.listeners]) listener(this.state)
  }
}

function connectionOf(state: ConnectionState): CallState['connection'] {
  switch (state) {
    case ConnectionState.Connected:
      return 'connected'
    case ConnectionState.Reconnecting:
    case ConnectionState.SignalReconnecting:
      return 'reconnecting'
    case ConnectionState.Disconnected:
      return 'disconnected'
    default:
      return 'connecting'
  }
}
