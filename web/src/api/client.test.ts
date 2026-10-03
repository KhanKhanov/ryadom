import { authJson, errorResponse, FakeBackend, jsonResponse, MemoryStorage, profileJson, requestJson } from '../testing/fakes'
import { ApiClient, type SessionEvent } from './client'
import { ApiError, NetworkError, SessionEndedError } from './errors'
import { createLock, SessionStore } from './session'

const MINUTE = 60_000

function setup() {
  const backend = new FakeBackend()
  const storage = new MemoryStorage()
  const store = new SessionStore(storage)
  let now = 1_000_000
  const api = new ApiClient({ baseUrl: '/api', store, lock: createLock(null), fetch: backend.fetch, now: () => now })
  const events: SessionEvent[] = []
  api.onSessionEvent((event) => events.push(event))
  return {
    backend,
    store,
    storage,
    api,
    events,
    advance: (ms: number) => {
      now += ms
    },
  }
}

async function loggedIn() {
  const context = setup()
  context.backend.on('POST', '/auth/dev', jsonResponse(200, authJson()))
  await context.api.loginDev('volunteer-1')
  return context
}

describe('ApiClient', () => {
  it('stores tokens after login and sends the access token', async () => {
    const { api, backend, store } = await loggedIn()
    backend.on('GET', '/me', jsonResponse(200, profileJson()))

    const profile = await api.getMe()

    expect(profile.displayName).toBe('Анна')
    expect(backend.calls('POST', '/auth/dev')[0].body).toEqual({ login: 'volunteer-1' })
    expect(backend.calls('GET', '/me')[0].authorization).toBe('Bearer access-1')
    expect(store.get()).toMatchObject({ userId: 'user-1', accessToken: 'access-1', refreshToken: 'refresh-1' })
  })

  it('refreshes an expiring access token before the request', async () => {
    const { api, backend, advance } = await loggedIn()
    backend
      .on('POST', '/auth/refresh', jsonResponse(200, authJson({ accessToken: 'access-2', refreshToken: 'refresh-2' })))
      .on('GET', '/me', jsonResponse(200, profileJson()))
    advance(15 * MINUTE)

    await api.getMe()

    expect(backend.calls('POST', '/auth/refresh')[0].body).toEqual({ refreshToken: 'refresh-1' })
    expect(backend.calls('GET', '/me')[0].authorization).toBe('Bearer access-2')
  })

  it('refreshes once and retries when the server answers 401', async () => {
    const { api, backend } = await loggedIn()
    backend
      .on('POST', '/auth/refresh', jsonResponse(200, authJson({ accessToken: 'access-2', refreshToken: 'refresh-2' })))
      .on('GET', '/me', (request) =>
        request.authorization === 'Bearer access-2' ? jsonResponse(200, profileJson()) : errorResponse(401, 'unauthorized'),
      )

    await api.getMe()

    expect(backend.calls('GET', '/me').map((r) => r.authorization)).toEqual(['Bearer access-1', 'Bearer access-2'])
  })

  it('refreshes only once for parallel requests: the refresh token is single-use', async () => {
    const { api, backend, advance } = await loggedIn()
    backend
      .on('POST', '/auth/refresh', jsonResponse(200, authJson({ accessToken: 'access-2', refreshToken: 'refresh-2' })))
      .on('GET', '/me', jsonResponse(200, profileJson()))
      .on('GET', '/requests/current', jsonResponse(204))
    advance(15 * MINUTE)

    await Promise.all([api.getMe(), api.getCurrentRequest(), api.getMe()])

    expect(backend.calls('POST', '/auth/refresh')).toHaveLength(1)
  })

  it('uses tokens refreshed by another tab instead of refreshing again', async () => {
    const { api, backend, store, advance } = await loggedIn()
    backend.on('GET', '/me', jsonResponse(200, profileJson()))
    advance(15 * MINUTE)
    // Другая вкладка уже обновила токены и сохранила их.
    store.set({ userId: 'user-1', accessToken: 'access-other-tab', accessTokenExpiresAt: 1_000_000 + 30 * MINUTE, refreshToken: 'refresh-other' })

    await api.getMe()

    expect(backend.calls('POST', '/auth/refresh')).toHaveLength(0)
    expect(backend.calls('GET', '/me')[0].authorization).toBe('Bearer access-other-tab')
  })

  it('ends the session when the refresh token is rejected', async () => {
    const { api, backend, store, events, advance } = await loggedIn()
    backend.on('POST', '/auth/refresh', errorResponse(401, 'invalid_refresh_token'))
    advance(15 * MINUTE)

    await expect(api.getMe()).rejects.toBeInstanceOf(SessionEndedError)

    expect(store.get()).toBeNull()
    expect(events).toEqual([{ kind: 'ended', reason: 'expired' }])
  })

  it('ends the session when the user is banned', async () => {
    const { api, backend, store, events } = await loggedIn()
    backend.on('GET', '/me', errorResponse(403, 'user_banned'))

    await expect(api.getMe()).rejects.toMatchObject({ reason: 'banned' })

    expect(store.get()).toBeNull()
    expect(events).toEqual([{ kind: 'ended', reason: 'banned' }])
  })

  it('keeps the session when the server is unreachable', async () => {
    const { api, backend, store, advance } = await loggedIn()
    backend.on('POST', '/auth/refresh', () => {
      throw new TypeError('Failed to fetch')
    })
    advance(15 * MINUTE)

    await expect(api.getMe()).rejects.toBeInstanceOf(NetworkError)

    expect(store.get()).not.toBeNull()
  })

  it('treats a proxy error without an API body as no connection', async () => {
    const { api, backend } = await loggedIn()
    backend.on('GET', '/me', () => new Response('Bad Gateway', { status: 502 }))

    await expect(api.getMe()).rejects.toBeInstanceOf(NetworkError)
  })

  it('passes unknown error codes through, so the UI shows a generic message', async () => {
    const { api, backend } = await loggedIn()
    backend.on('POST', '/requests/request-1/accept', errorResponse(409, 'volunteer_on_break'))

    const error = await api.acceptRequest('request-1').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'volunteer_on_break' })
  })

  it('returns null when there is no current request', async () => {
    const { api, backend } = await loggedIn()
    backend.on('GET', '/requests/current', jsonResponse(204))

    expect(await api.getCurrentRequest()).toBeNull()
  })

  it('cancels and rates requests', async () => {
    const { api, backend } = await loggedIn()
    backend
      .on('DELETE', '/requests/request-1', jsonResponse(200, requestJson({ status: 'ended' })))
      .on('POST', '/requests/request-1/rating', jsonResponse(204))

    expect((await api.cancelRequest('request-1')).status).toBe('ended')
    await api.rateRequest('request-1', true)

    expect(backend.calls('POST', '/requests/request-1/rating')[0].body).toEqual({ helped: true })
  })

  it('logs out: forgets tokens and revokes the refresh token', async () => {
    const { api, backend, store, events } = await loggedIn()
    backend.on('POST', '/auth/logout', jsonResponse(204))

    await api.logout()

    expect(store.get()).toBeNull()
    expect(backend.calls('POST', '/auth/logout')[0].body).toEqual({ refreshToken: 'refresh-1' })
    expect(events).toEqual([{ kind: 'ended', reason: 'logged_out' }])
  })

  it('notices logout and another user in other tabs', async () => {
    const { api, store, events } = await loggedIn()
    const target = new EventTarget()
    api.watchOtherTabs(target as unknown as Window)
    const storageChanged = () => target.dispatchEvent(Object.assign(new Event('storage'), { key: 'ryadom.session' }))

    store.set({ userId: 'user-2', accessToken: 'a', accessTokenExpiresAt: 2_000_000, refreshToken: 'r' })
    storageChanged()
    store.clear()
    storageChanged()
    storageChanged()

    expect(events).toEqual([{ kind: 'user_changed' }, { kind: 'ended', reason: 'logged_out' }])
  })

  it('reports a login error from the server', async () => {
    const { api, backend } = setup()
    backend.on('POST', '/auth/oauth/yandex', errorResponse(401, 'oauth_failed'))

    await expect(api.loginYandex('code', 'v'.repeat(43))).rejects.toMatchObject({ code: 'oauth_failed' })
    expect(backend.calls('POST', '/auth/oauth/yandex')[0].body).toEqual({ code: 'code', codeVerifier: 'v'.repeat(43) })
  })
})
