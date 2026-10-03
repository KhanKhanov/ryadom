import { authJson, callJson, profileJson, requestJson } from '../testing/fakes'
import { isActive, isCallActive, parseAuthResponse, parseHelpRequest, parseProfile, UnexpectedResponseError } from './types'

describe('parseProfile', () => {
  it('reads the fields the web app uses', () => {
    expect(parseProfile(profileJson())).toEqual({
      id: 'user-1',
      role: 'volunteer',
      displayName: 'Анна',
      timezone: 'Europe/Moscow',
      doNotDisturb: { from: '22:00', to: '08:00' },
      notificationsEnabled: true,
    })
  })

  it('ignores unknown fields from a newer server', () => {
    const profile = parseProfile(profileJson({ avatarUrl: 'https://example.org/a.png', rating: { score: 5 } }))
    expect(profile).not.toHaveProperty('avatarUrl')
  })

  it('keeps an unknown role instead of failing', () => {
    expect(parseProfile(profileJson({ role: 'moderator' })).role).toBe('unknown')
    expect(parseProfile(profileJson({ role: null })).role).toBeNull()
  })

  it('accepts an empty display name', () => {
    expect(parseProfile(profileJson({ displayName: null })).displayName).toBeNull()
  })

  it('fails when a required field is missing', () => {
    const broken: Record<string, unknown> = profileJson()
    delete broken.notificationsEnabled
    expect(() => parseProfile(broken)).toThrow(UnexpectedResponseError)
    expect(() => parseProfile('not an object')).toThrow(UnexpectedResponseError)
  })
})

describe('parseHelpRequest', () => {
  it('reads call credentials', () => {
    expect(parseHelpRequest(requestJson({ status: 'accepted', call: callJson }))).toEqual({
      id: 'request-1',
      status: 'accepted',
      language: 'ru',
      call: callJson,
    })
  })

  it('treats an unknown status as unknown, which is neither active nor a call', () => {
    const request = parseHelpRequest(requestJson({ status: 'paused' }))
    expect(request.status).toBe('unknown')
    expect(isActive(request.status)).toBe(false)
    expect(isCallActive(request.status)).toBe(false)
  })
})

describe('parseAuthResponse', () => {
  it('reads tokens and the profile', () => {
    const auth = parseAuthResponse({ ...authJson(), extra: true })
    expect(auth.accessToken).toBe('access-1')
    expect(auth.accessTokenExpiresIn).toBe(900)
    expect(auth.refreshToken).toBe('refresh-1')
    expect(auth.user.id).toBe('user-1')
  })
})

describe('request status helpers', () => {
  it('knows active and call statuses', () => {
    expect(['searching', 'accepted', 'in_call'].every((s) => isActive(s as never))).toBe(true)
    expect(['ended', 'no_answer', 'cancelled'].some((s) => isActive(s as never))).toBe(false)
    expect(isCallActive('searching')).toBe(false)
    expect(isCallActive('in_call')).toBe(true)
  })
})
