// Состояние кабинета волонтёра: входящие вызовы, принятие, звонок, оценка.
// Чистая функция без React и сети — её легко проверить тестами (volunteerState.test.ts).

import type { RealtimeEvent } from '../api/realtime'
import { isCallActive, type HelpRequest } from '../api/types'

/** Сообщение для экранного диктора и строки состояния. */
export type NoticeKey =
  | 'incomingAnnouncement'
  | 'noticeTaken'
  | 'noticeAcceptedElsewhere'
  | 'noticeCancelled'
  | 'noticeNoAnswer'
  | 'errorRequestTaken'
  | 'errorRequestClosed'
  | 'callEndedByOther'
  | 'noticeCallEnded'
  | 'noticeCallNotStarted'

export type VolunteerState = {
  /** Вызовы, которые можно принять, — в порядке поступления. */
  incoming: HelpRequest[]
  /** Какой вызов сейчас принимается (запрос отправлен, ответа ещё нет). */
  accepting: string | null
  /** Идущий звонок: запрос с данными для входа в комнату. */
  call: HelpRequest | null
  /** Собеседник хоть раз был в идущем звонке — значит, разговор состоялся и его можно оценить. */
  remoteJoined: boolean
  /** Волонтёр сам завершает идущий звонок, ответа сервера ещё нет. */
  ending: boolean
  /** Звонок только что закончился — показываем вопрос «Удалось помочь?». */
  finished: { requestId: string; byOther: boolean } | null
  /** Последнее сообщение; `id` растёт, чтобы одинаковое сообщение тоже прозвучало. */
  notice: { id: number; key: NoticeKey } | null
}

export const initialVolunteerState: VolunteerState = {
  incoming: [],
  accepting: null,
  call: null,
  remoteJoined: false,
  ending: false,
  finished: null,
  notice: null,
}

export type VolunteerAction =
  /** Событие WebSocket. */
  | { type: 'event'; event: RealtimeEvent }
  /** Свежее состояние запроса из REST API (после переподключения). */
  | { type: 'requestUpdated'; request: HelpRequest }
  /** Звонок, найденный через GET /requests/current после перезагрузки страницы. */
  | { type: 'callRestored'; request: HelpRequest }
  | { type: 'skip'; requestId: string }
  | { type: 'acceptStarted'; requestId: string }
  | { type: 'acceptSucceeded'; request: HelpRequest }
  /** Принять не удалось: `taken`/`closed` — вызов больше не актуален, `retry` — можно попробовать ещё. */
  | { type: 'acceptFailed'; requestId: string; reason: 'taken' | 'closed' | 'retry' }
  /** Собеседник появился в звонке (по данным LiveKit). */
  | { type: 'remoteJoined'; requestId: string }
  /** Волонтёр нажал «Завершить звонок», запрос к серверу отправлен. */
  | { type: 'endStarted'; requestId: string }
  /** Завершить звонок не удалось (нет связи) — звонок продолжается. */
  | { type: 'endFailed'; requestId: string }
  /** Волонтёр сам завершил звонок: ответ сервера. */
  | { type: 'callEnded'; request: HelpRequest }
  | { type: 'ratingDone' }

export function volunteerReducer(state: VolunteerState, action: VolunteerAction): VolunteerState {
  switch (action.type) {
    case 'event':
      return onEvent(state, action.event)
    case 'requestUpdated':
      return onRequestUpdate(state, action.request, null)
    case 'callRestored':
      return state.call?.id === action.request.id ? state : startCall(state, action.request)
    case 'skip':
      return { ...state, incoming: without(state.incoming, action.requestId) }
    case 'acceptStarted':
      return { ...state, accepting: action.requestId }
    case 'acceptSucceeded':
      return startCall(state, action.request)
    case 'acceptFailed': {
      const accepting = state.accepting === action.requestId ? null : state.accepting
      if (action.reason === 'retry') return { ...state, accepting }
      const key = action.reason === 'taken' ? 'errorRequestTaken' : 'errorRequestClosed'
      return notify({ ...state, accepting, incoming: without(state.incoming, action.requestId) }, key)
    }
    case 'remoteJoined':
      return state.call?.id === action.requestId ? { ...state, remoteJoined: true } : state
    case 'endStarted':
      return state.call?.id === action.requestId ? { ...state, ending: true } : state
    case 'endFailed':
      return state.call?.id === action.requestId ? { ...state, ending: false } : state
    case 'callEnded':
      // Событие request.ended могло прийти раньше ответа — тогда звонок уже закрыт.
      return state.call?.id === action.request.id ? closeCall({ ...state, ending: true }) : state
    case 'ratingDone':
      return { ...state, finished: null }
  }
}

function onEvent(state: VolunteerState, event: RealtimeEvent): VolunteerState {
  const { request } = event
  if (event.type === 'request.incoming') {
    const known = state.incoming.some((r) => r.id === request.id) || state.call?.id === request.id
    if (known || request.status !== 'searching') return state
    return notify({ ...state, incoming: [...state.incoming, request] }, 'incomingAnnouncement')
  }
  return onRequestUpdate(state, request, eventNotice(event.type))
}

function eventNotice(type: RealtimeEvent['type']): NoticeKey | null {
  switch (type) {
    case 'request.taken':
      return 'noticeTaken'
    // Волонтёру request.accepted приходит, когда он сам принял вызов в другой вкладке или на другом устройстве.
    case 'request.accepted':
      return 'noticeAcceptedElsewhere'
    case 'request.cancelled':
      return 'noticeCancelled'
    case 'request.no_answer':
      return 'noticeNoAnswer'
    default:
      return null
  }
}

/** Запрос изменился: убрать его из входящих, если его больше нельзя принять, и закончить звонок, если он закрыт. */
function onRequestUpdate(state: VolunteerState, request: HelpRequest, key: NoticeKey | null): VolunteerState {
  if (state.call?.id === request.id) {
    return isCallActive(request.status) ? state : closeCall(state)
  }
  if (request.status === 'searching' || !state.incoming.some((r) => r.id === request.id)) return state
  const updated = { ...state, incoming: without(state.incoming, request.id) }
  // Пока волонтёр нажимает «Принять», сообщение об ошибке придёт в ответе сервера.
  return key && state.accepting !== request.id ? notify(updated, key) : updated
}

function startCall(state: VolunteerState, request: HelpRequest): VolunteerState {
  // Во время звонка другие вызовы не принять, а к его концу они уже закроются: поиск длится около минуты.
  return { ...state, call: request, remoteJoined: false, ending: false, accepting: null, incoming: [], finished: null }
}

/**
 * Звонок закрыт — им самим или собеседником. «Удалось помочь?» спрашиваем, только если разговор был:
 * если незрячий отменил вызов или не подключился, оценивать нечего (как на Android, docs/ARCHITECTURE.md, раздел 7).
 */
function closeCall(state: VolunteerState): VolunteerState {
  const call = state.call
  if (!call) return state
  const byOther = !state.ending
  const closed: VolunteerState = { ...state, call: null, remoteJoined: false, ending: false }
  if (!state.remoteJoined) return notify(closed, byOther ? 'noticeCallNotStarted' : 'noticeCallEnded')
  const finished: VolunteerState = { ...closed, finished: { requestId: call.id, byOther } }
  return byOther ? notify(finished, 'callEndedByOther') : finished
}

function notify(state: VolunteerState, key: NoticeKey): VolunteerState {
  return { ...state, notice: { id: (state.notice?.id ?? 0) + 1, key } }
}

function without(requests: HelpRequest[], id: string): HelpRequest[] {
  return requests.filter((r) => r.id !== id)
}
