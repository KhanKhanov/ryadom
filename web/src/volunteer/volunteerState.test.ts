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

/** Вызов принят, и собеседник уже появился в звонке. */
const talking: VolunteerAction[] = [
  { type: 'acceptSucceeded', request: accepted },
  { type: 'remoteJoined', requestId: 'request-1' },
]

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
    // Волонтёр принял вызов в другой вкладке или на другом устройстве.
    ['request.accepted', 'accepted', 'noticeAcceptedElsewhere'],
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

  it('keeps the accepting tab quiet when its own acceptance arrives as an event', () => {
    const state = reduce(event('request.incoming'), { type: 'acceptStarted', requestId: 'request-1' }, event('request.accepted', { status: 'accepted' }), {
      type: 'acceptSucceeded',
      request: accepted,
    })

    expect(state.call).toEqual(accepted)
    expect(state.notice?.key).toBe('incomingAnnouncement')
    expect(volunteerReducer(state, event('request.accepted', { status: 'accepted' }))).toBe(state)
  })

  it('shows the rating question when the other side ends the call', () => {
    const state = reduce(...talking, event('request.ended', { status: 'ended' }))

    expect(state.call).toBeNull()
    expect(state.finished).toEqual({ requestId: 'request-1', byOther: true })
    expect(state.notice?.key).toBe('callEndedByOther')
  })

  it('shows the rating question when the volunteer ends the call', () => {
    const state = reduce(...talking, { type: 'endStarted', requestId: 'request-1' }, { type: 'callEnded', request: request({ status: 'ended' }) })

    expect(state.call).toBeNull()
    expect(state.finished).toEqual({ requestId: 'request-1', byOther: false })
  })

  it('does not blame the other side when the end event arrives before the answer to the volunteer’s own end', () => {
    const state = reduce(
      ...talking,
      { type: 'endStarted', requestId: 'request-1' },
      event('request.ended', { status: 'ended' }),
      { type: 'callEnded', request: request({ status: 'ended' }) },
    )

    expect(state.finished).toEqual({ requestId: 'request-1', byOther: false })
    expect(state.notice).toBeNull()
  })

  it('blames the other side again after the volunteer failed to end the call', () => {
    const state = reduce(...talking, { type: 'endStarted', requestId: 'request-1' }, { type: 'endFailed', requestId: 'request-1' }, event('request.ended', { status: 'ended' }))

    expect(state.finished).toEqual({ requestId: 'request-1', byOther: true })
  })

  it.each([
    ['request.cancelled', 'cancelled'],
    ['request.ended', 'ended'],
  ] as const)('does not ask for a rating when the other side never joined (%s)', (type, status) => {
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, event(type, { status }))

    expect(state.call).toBeNull()
    expect(state.finished).toBeNull()
    expect(state.notice?.key).toBe('noticeCallNotStarted')
  })

  it('does not ask for a rating when the volunteer ends the call before the other side joined', () => {
    const state = reduce({ type: 'acceptSucceeded', request: accepted }, { type: 'endStarted', requestId: 'request-1' }, { type: 'callEnded', request: request({ status: 'ended' }) })

    expect(state.finished).toBeNull()
    expect(state.notice?.key).toBe('noticeCallEnded')
  })

  it('forgets who joined when the next call starts', () => {
    const state = reduce(...talking, { type: 'endStarted', requestId: 'request-1' }, { type: 'callEnded', request: request({ status: 'ended' }) }, {
      type: 'acceptSucceeded',
      request: request({ id: 'request-2', status: 'accepted', call: callJson }),
    })

    expect(state.remoteJoined).toBe(false)
    expect(state.ending).toBe(false)
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
    const rated = reduce(...talking, { type: 'callEnded', request: request({ status: 'ended' }) }, { type: 'ratingDone' })
    expect(rated.finished).toBeNull()
  })

  describe('calls waiting for an answer from the server', () => {
    const synced = (...requests: HelpRequest[]): VolunteerAction => ({ type: 'incomingSynced', requests })

    it('adds calls that came while there was no connection and rings', () => {
      const state = reduce(synced(request(), request({ id: 'request-2' })))

      expect(state.incoming.map((r) => r.id)).toEqual(['request-1', 'request-2'])
      expect(state.notice?.key).toBe('incomingAnnouncement')
    })

    it('removes calls that closed meanwhile without a new announcement', () => {
      const shown = reduce(event('request.incoming'), event('request.incoming', { id: 'request-2' }))

      const state = volunteerReducer(shown, synced(request({ id: 'request-2' })))

      expect(state.incoming.map((r) => r.id)).toEqual(['request-2'])
      expect(state.notice).toEqual(shown.notice)
    })

    it('does not bring back a call skipped in this tab', () => {
      const state = reduce(event('request.incoming'), { type: 'skip', requestId: 'request-1' }, synced(request()))

      expect(state.incoming).toEqual([])
      // И повторное событие о нём тоже не вернёт.
      expect(volunteerReducer(state, event('request.incoming')).incoming).toEqual([])
    })

    it('keeps the call being accepted: the answer to Accept decides', () => {
      const state = reduce(event('request.incoming'), { type: 'acceptStarted', requestId: 'request-1' }, synced())

      expect(state.incoming.map((r) => r.id)).toEqual(['request-1'])
    })

    it('ignores the list during a call', () => {
      const inCall = reduce(...talking)

      expect(volunteerReducer(inCall, synced(request({ id: 'request-2' })))).toBe(inCall)
    })
  })
})
