import { vi } from 'vitest'
import { requestJson } from '../testing/fakes'
import type { AccessToken } from './client'
import { SessionEndedError } from './errors'
import { RealtimeConnection, reconnectPause, type RealtimeEvent, type RealtimeStatus } from './realtime'

/** Поддельный WebSocket: тест открывает, присылает сообщения и закрывает его «со стороны сервера». */
class FakeSocket {
  sent: unknown[] = []
  closedByClient: number | null = null
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  readonly url: string

  constructor(url: string) {
    this.url = url
  }

  send(data: string) {
    this.sent.push(JSON.parse(data))
  }
  close(code?: number) {
    this.closedByClient = code ?? 1000
  }

  serverOpens() {
    this.onopen?.()
  }
  serverSends(message: unknown) {
    this.onmessage?.({ data: typeof message === 'string' ? message : JSON.stringify(message) })
  }
  serverCloses(code: number) {
    this.onclose?.({ code })
  }
}

const NOW = 1_000_000

function setup(options: { tokens?: AccessToken[]; random?: number } = {}) {
  const sockets: FakeSocket[] = []
  const tokens = options.tokens ?? [{ value: 'access-1', expiresAt: NOW + 15 * 60_000 }]
  let tokenIndex = 0
  const nextToken = async () => tokens[Math.min(tokenIndex++, tokens.length - 1)]
  const auth = {
    accessToken: vi.fn(nextToken),
    refreshNow: vi.fn(nextToken),
    endSession: vi.fn(),
  }
  const statuses: RealtimeStatus[] = []
  const events: RealtimeEvent[] = []
  const onReady = vi.fn()
  const connection = new RealtimeConnection({
    url: 'ws://localhost/api/ws',
    auth,
    handlers: { onReady, onEvent: (event) => events.push(event), onStatus: (status) => statuses.push(status) },
    createSocket: (url) => {
      const socket = new FakeSocket(url)
      sockets.push(socket)
      return socket as unknown as WebSocket
    },
    now: () => NOW,
    // Без разброса пауз (reconnectPause), если тест не задал другое.
    random: () => options.random ?? 0,
  })
  return { connection, sockets, auth, statuses, events, onReady, last: () => sockets[sockets.length - 1] }
}

/** Даёт выполниться ожидающим промисам (получение токена). */
const flush = () => vi.advanceTimersByTimeAsync(0)

describe('RealtimeConnection', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('logs in with the first message and reports ready', async () => {
    const { connection, last, statuses, onReady } = setup()
    connection.start()
    last().serverOpens()
    await flush()

    expect(last().url).toBe('ws://localhost/api/ws')
    expect(last().sent).toEqual([{ type: 'auth', accessToken: 'access-1' }])

    last().serverSends({ type: 'ready' })

    expect(statuses).toEqual(['connecting', 'connected'])
    expect(onReady).toHaveBeenCalledOnce()
  })

  it('delivers request events and skips unknown or broken messages', async () => {
    const { connection, last, events } = setup()
    connection.start()
    last().serverOpens()
    await flush()
    last().serverSends({ type: 'ready' })

    last().serverSends({ type: 'request.incoming', request: requestJson(), extra: 1 })
    last().serverSends({ type: 'request.paused', request: requestJson() })
    last().serverSends({ type: 'request.taken', request: { id: 'no status' } })
    last().serverSends('not json')
    last().serverSends({ noType: true })

    expect(events).toHaveLength(1)
    expect(events[0].type).toBe('request.incoming')
    expect(events[0].request.id).toBe('request-1')
  })

  it('reconnects with growing pauses after the connection drops', async () => {
    const { connection, sockets, last, statuses } = setup()
    connection.start()
    last().serverCloses(1006)
    expect(statuses.at(-1)).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(999)
    expect(sockets).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(sockets).toHaveLength(2)

    last().serverCloses(1006)
    await vi.advanceTimersByTimeAsync(1_999)
    expect(sockets).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(sockets).toHaveLength(3)

    // После успешного входа пауза снова короткая.
    last().serverOpens()
    await flush()
    last().serverSends({ type: 'ready' })
    last().serverCloses(1006)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(sockets).toHaveLength(4)
  })

  it('spreads reconnection pauses but never makes them longer', async () => {
    expect([0, 1, 2, 3, 4, 5].map((failures) => reconnectPause(failures, 0))).toEqual([1_000, 2_000, 5_000, 10_000, 30_000, 30_000])
    expect([0, 1, 2, 3, 4].map((failures) => reconnectPause(failures, 1))).toEqual([750, 1_500, 3_750, 7_500, 22_500])

    // Клиент с наибольшим разбросом приходит раньше обещанной секунды.
    const { connection, sockets, last } = setup({ random: 1 })
    connection.start()
    last().serverCloses(1006)
    await vi.advanceTimersByTimeAsync(749)
    expect(sockets).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(sockets).toHaveLength(2)
  })

  it('refreshes the token and reconnects at once when the server rejects it', async () => {
    const { connection, sockets, last, auth } = setup({
      tokens: [
        { value: 'access-1', expiresAt: NOW + 15 * 60_000 },
        { value: 'access-2', expiresAt: NOW + 15 * 60_000 },
      ],
    })
    connection.start()
    last().serverOpens()
    await flush()
    last().serverSends({ type: 'ready' })

    last().serverCloses(4401)
    await flush()
    last().serverOpens()
    await flush()

    expect(sockets).toHaveLength(2)
    expect(auth.refreshNow).toHaveBeenCalledOnce()
    expect(last().sent).toEqual([{ type: 'auth', accessToken: 'access-2' }])
  })

  it('stops and ends the session when the user is banned', async () => {
    const { connection, sockets, last, auth } = setup()
    connection.start()
    last().serverCloses(4403)
    await vi.advanceTimersByTimeAsync(60_000)

    expect(auth.endSession).toHaveBeenCalledWith('banned')
    expect(sockets).toHaveLength(1)
  })

  it('sends a fresh token before the old one expires', async () => {
    const { connection, last } = setup({
      tokens: [
        { value: 'access-1', expiresAt: NOW + 15 * 60_000 },
        { value: 'access-2', expiresAt: NOW + 30 * 60_000 },
      ],
    })
    connection.start()
    last().serverOpens()
    await flush()

    await vi.advanceTimersByTimeAsync(14 * 60_000)

    expect(last().sent).toEqual([
      { type: 'auth', accessToken: 'access-1' },
      { type: 'auth', accessToken: 'access-2' },
    ])
  })

  it('stops quietly when the session has ended', async () => {
    const { connection, sockets, last, auth } = setup()
    auth.accessToken.mockRejectedValueOnce(new SessionEndedError('expired'))
    connection.start()
    last().serverOpens()
    await flush()
    last().serverCloses(4401)
    await vi.advanceTimersByTimeAsync(60_000)

    expect(sockets).toHaveLength(1)
    expect(last().closedByClient).toBe(1000)
  })

  it('closes the socket and does not reconnect after stop()', async () => {
    const { connection, sockets, last } = setup()
    connection.start()
    connection.stop()
    last().serverCloses(1000)
    await vi.advanceTimersByTimeAsync(60_000)

    expect(last().closedByClient).toBe(1000)
    expect(sockets).toHaveLength(1)
  })
})
