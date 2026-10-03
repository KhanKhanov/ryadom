// Состояние кабинета волонтёра: входящие вызовы, принятие, звонок, оценка.
// Чистая функция без React и сети — её легко проверить тестами (volunteerState.test.ts).

import type { RealtimeEvent } from '../api/realtime'
import { isCallActive, type HelpRequest } from '../api/types'

/** Сообщение для экранного диктора и строки состояния. */
export type NoticeKey =
  | 'incomingAnnouncement'
  | 'noticeTaken'
  | 'noticeCancelled'
  | 'noticeNoAnswer'
  | 'errorRequestTaken'
  | 'errorRequestClosed'
  | 'callEndedByOther'

export type VolunteerState = {
  /** Вызовы, которые можно принять, — в порядке поступления. */
  incoming: HelpRequest[]
  /** Какой вызов сейчас принимается (запрос отправлен, ответа ещё нет). */
  accepting: string | null
  /** Идущий звонок: запрос с данными для входа в комнату. */
  call: HelpRequest | null
  /** Звонок только что закончился — показываем вопрос «Удалось помочь?». */
  finished: { requestId: string; byOther: boolean } | null
  /** Последнее сообщение; `id` растёт, чтобы одинаковое сообщение тоже прозвучало. */
  notice: { id: number; key: NoticeKey } | null
}

export const initialVolunteerState: VolunteerState = {
  incoming: [],
  accepting: null,
  call: null,
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
  /** Волонтёр сам завершил звонок. */
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
    case 'callEnded':
      return state.call?.id === action.request.id ? { ...state, call: null, finished: { requestId: action.request.id, byOther: false } } : state
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
  const key: NoticeKey | null =
    event.type === 'request.taken'
      ? 'noticeTaken'
      : event.type === 'request.cancelled'
        ? 'noticeCancelled'
        : event.type === 'request.no_answer'
          ? 'noticeNoAnswer'
          : null
  return onRequestUpdate(state, request, key)
}

/** Запрос изменился: убрать его из входящих, если его больше нельзя принять, и закончить звонок, если он закрыт. */
function onRequestUpdate(state: VolunteerState, request: HelpRequest, key: NoticeKey | null): VolunteerState {
  if (state.call?.id === request.id) {
    if (isCallActive(request.status)) return state
    const ended: VolunteerState = { ...state, call: null, finished: { requestId: request.id, byOther: true } }
    return notify(ended, 'callEndedByOther')
  }
  if (request.status === 'searching' || !state.incoming.some((r) => r.id === request.id)) return state
  const updated = { ...state, incoming: without(state.incoming, request.id) }
  // Пока волонтёр нажимает «Принять», сообщение об ошибке придёт в ответе сервера.
  return key && state.accepting !== request.id ? notify(updated, key) : updated
}

function startCall(state: VolunteerState, request: HelpRequest): VolunteerState {
  // Во время звонка другие вызовы не принять, а к его концу они уже закроются: поиск длится около минуты.
  return { ...state, call: request, accepting: null, incoming: [], finished: null }
}

function notify(state: VolunteerState, key: NoticeKey): VolunteerState {
  return { ...state, notice: { id: (state.notice?.id ?? 0) + 1, key } }
}

function without(requests: HelpRequest[], id: string): HelpRequest[] {
  return requests.filter((r) => r.id !== id)
}
