import type { RealtimeEventType } from '../api/realtime'
import { parseHelpRequest, type HelpRequest } from '../api/types'
import { callJson, requestJson } from '../testing/fakes'
import { initialVolunteerState, volunteerReducer, type VolunteerAction, type VolunteerState } from './volunteerState'

function request(overrides: Record<string, unknown> = {}): HelpRequest {
  return parseHelpRequest(requestJson(overrides))
}

function event(type: RealtimeEventType, overrides: Record<string, unknown> = {}): VolunteerAction {
  return { type: 'event', event: { type, request: request(overrides) } }
}

function reduce(...actions: VolunteerAction[]): VolunteerState {
  return actions.reduce(volunteerReducer, initialVolunteerState)
}

const accepted = request({ status: 'accepted', call: callJson })

describe('volunteerReducer', () => {
  it('adds incoming calls once and announces them', () => {
    const state = reduce(event('request.incoming'), event('request.incoming'), event('request.incoming', { id: 'request-2' }))

    expect(state.incoming.map((r) => r.id)).toEqual(['request-1', 'request-2'])
    expect(state.notice).toEqual({ id: 2, key: 'incomingAnnouncement' })
  })

  it('ignores an incoming event for a request that is no longer searching', () => {
    expect(reduce(event('request.incoming', { status: 'cancelled' })).incoming).toEqual([])
  })

  it.each([
    ['request.taken', 'accepted', 'noticeTaken'],
    ['request.cancelled', 'cancelled', 'noticeCancelled'],
    ['request.no_answer', 'no_answer', 'noticeNoAnswer'],
  ] as const)('removes the call on %s', (type, status, notice) => {
    const state = reduce(event('request.incoming'), event(type, { status }))

    expect(state.incoming).toEqual([])
    expect(state.notice?.key).toBe(notice)
  })

  it('does not announce a closed call while the volunteer is accepting it: the server answer will explain', () => {
    const state = reduce(event('request.incoming'), { type: 'acceptStarted', requestId: 'request-1' }, event('request.taken', { status: 'accepted' }))

    expect(state.incoming).toEqual([])
    expect(state.notice?.key).toBe('incomingAnnouncement')
  })

  it('starts the call after accepting and forgets other incoming calls', () => {
    const state = reduce(
      event('request.incoming'),
      event('request.incoming', { id: 'request-2' }),
      { type: 'acceptStarted', requestId: 'request-1' },
      { type: 'acceptSucceeded', request: accepted },
    )

    expect(state.call).toEqual(accepted)
    expect(state.accepting).toBeNull()
    expect(state.incoming).toEqual([])
  })

  it.each([
    ['taken', 'errorRequestTaken'],
    ['closed', 'errorRequestClosed'],
  ] as const)('removes the call when accepting fails because it is %s', (reason, notice) => {
    const state = reduce(event('request.incoming'), { type: 'acceptStarted', requestId: 'request-1' }, { type: 'acceptFailed', requestId: 'request-1', reason })

    expect(state.incoming).toEqual([])
    expect(state.accepting).toBeNull()
    expect(state.notice?.key).toBe(notice)
  })

  it('keeps the call when accepting can be retried', () => {
    const state = reduce(event('request.incoming'), { type: 'acceptStarted', requestId: 'request-1' }, { type: 'acceptFailed', requestId: 'request-1', reason: 'retry' })

    expect(state.incoming).toHaveLength(1)
    expect(state.accepting).toBeNull()
  })

  it('shows the rating question when the other side ends the call', () => {
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, event('request.ended', { status: 'ended' }))

    expect(state.call).toBeNull()
    expect(state.finished).toEqual({ requestId: 'request-1', byOther: true })
    expect(state.notice?.key).toBe('callEndedByOther')
  })

  it('shows the rating question when the volunteer ends the call', () => {
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, { type: 'callEnded', request: request({ status: 'ended' }) })

    expect(state.call).toBeNull()
    expect(state.finished).toEqual({ requestId: 'request-1', byOther: false })
  })

  it('keeps the call while it is still active, for example after the call started', () => {
    const inCall = request({ status: 'in_call', call: callJson })
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, { type: 'requestUpdated', request: inCall })

    expect(state.call).toEqual(accepted)
  })

  it('treats an unknown status of the current call as closed', () => {
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, { type: 'requestUpdated', request: request({ status: 'archived' }) })

    expect(state.call).toBeNull()
  })

  it('restores a call found after reloading the page, once', () => {
    const restored = reduce({ type: 'callRestored', request: accepted })
    expect(restored.call).toEqual(accepted)
    expect(volunteerReducer(restored, { type: 'callRestored', request: accepted })).toBe(restored)
  })

  it('skips a call and finishes rating', () => {
    expect(reduce(event('request.incoming'), { type: 'skip', requestId: 'request-1' }).incoming).toEqual([])
    const rated = reduce({ type: 'acceptSucceeded', request: accepted }, { type: 'callEnded', request: request({ status: 'ended' }) }, { type: 'ratingDone' })
    expect(rated.finished).toBeNull()
  })
})
