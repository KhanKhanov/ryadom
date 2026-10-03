import { act, screen, waitFor, within } from '@testing-library/react'
import { vi } from 'vitest'
import { callJson, createTestServices, errorResponse, jsonResponse, requestJson } from '../testing/fakes'
import { announced, renderScreen } from '../testing/render'
import { callStatusKey, initialCallState } from './call'
import { CallScreen } from './CallScreen'

function renderCall(options: { mode?: 'volunteer' | 'blind'; onEnd?: () => Promise<void> } = {}) {
  const services = createTestServices({ loggedIn: true })
  const onEnd = vi.fn(options.onEnd ?? (async () => undefined))
  const onClosed = vi.fn()
  const rendered = renderScreen(
    services,
    <CallScreen requestId="request-1" credentials={callJson} mode={options.mode ?? 'volunteer'} onEnd={onEnd} onClosed={onClosed} />,
  )
  return { services, onEnd, onClosed, call: services.calls.last, ...rendered }
}

/** Видимый экран — без невидимой области для экранного диктора, где повторяется тот же текст. */
const page = () => within(document.querySelector('section')!)

describe('CallScreen', () => {
  it('says and announces what is happening with the call', () => {
    const { call } = renderCall()
    expect(page().getByText('Подключаемся к звонку…')).toBeInTheDocument()

    act(() => call.update({ connection: 'connected', microphone: 'on' }))
    expect(page().getByText('Ждём, пока собеседник подключится…')).toBeInTheDocument()
    expect(announced()).toBe('Ждём, пока собеседник подключится…')

    act(() => call.update({ remote: 'present', remoteName: 'Иван', remoteVideo: true }))
    expect(page().getByText('Звонок идёт.')).toBeInTheDocument()
    expect(screen.getByText('Собеседник: Иван')).toBeInTheDocument()
    expect(screen.queryByText('Видео пока нет')).not.toBeInTheDocument()

    act(() => call.update({ remote: 'left' }))
    expect(page().getByText('Собеседник отключился. Можно подождать или завершить звонок.')).toBeInTheDocument()

    act(() => call.update({ connection: 'reconnecting' }))
    expect(announced()).toBe('Связь прервалась. Переподключаемся…')
  })

  it('turns the microphone off and on', async () => {
    const { call, user } = renderCall()
    act(() => call.update({ connection: 'connected', microphone: 'on' }))

    await user.click(screen.getByRole('button', { name: 'Выключить микрофон' }))

    expect(call.microphoneCalls).toEqual([false])
    expect(screen.getByRole('button', { name: 'Включить микрофон' })).toBeInTheDocument()
    expect(announced()).toBe('Микрофон выключен')
  })

  it('explains a blocked microphone and does not claim it was turned on', async () => {
    const { call, user } = renderCall()
    call.microphoneBlocked = true
    act(() => call.update({ connection: 'connected', microphone: 'blocked' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Нет доступа к микрофону.')

    await user.click(screen.getByRole('button', { name: 'Включить микрофон' }))

    expect(call.microphoneCalls).toEqual([true])
    expect(announced()).toContain('Нет доступа к микрофону.')
  })

  it('offers to turn on the sound when the browser blocked it', async () => {
    const { call, user } = renderCall()
    act(() => call.update({ connection: 'connected', audioBlocked: true }))

    await user.click(screen.getByRole('button', { name: 'Включить звук' }))

    expect(call.audioStarted).toBe(true)
    expect(screen.queryByRole('button', { name: 'Включить звук' })).not.toBeInTheDocument()
  })

  it('reconnects with fresh credentials after losing the connection', async () => {
    const { services, call, user } = renderCall()
    const fresh = { ...callJson, token: 'fresh-token' }
    services.backend.on('GET', '/requests/request-1', jsonResponse(200, requestJson({ status: 'in_call', call: fresh })))
    act(() => call.update({ connection: 'disconnected' }))

    await user.click(screen.getByRole('button', { name: 'Подключиться снова' }))

    await waitFor(() => expect(services.calls.all).toHaveLength(2))
    expect(call.disconnected).toBe(true)
    expect(services.calls.last.credentials).toEqual(fresh)
    expect(services.calls.last.connected).toBe(true)
  })

  it('reports a call that was closed while the connection was lost', async () => {
    const { services, call, onClosed, user } = renderCall()
    services.backend.on('GET', '/requests/request-1', jsonResponse(200, requestJson({ status: 'ended' })))
    act(() => call.update({ connection: 'disconnected', replaced: true }))
    expect(page().getByText('Звонок открыт в другой вкладке.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Подключиться снова' }))

    await waitFor(() => expect(onClosed).toHaveBeenCalledWith(expect.objectContaining({ status: 'ended' })))
  })

  it('shows an error if the call could not be ended', async () => {
    const { user, onEnd } = renderCall({
      onEnd: async () => {
        throw new (await import('../api/errors')).NetworkError()
      },
    })

    await user.click(screen.getByRole('button', { name: 'Завершить звонок' }))

    expect(onEnd).toHaveBeenCalledOnce()
    expect(await screen.findByRole('alert')).toHaveTextContent('Нет связи с сервером.')
    expect(screen.getByRole('button', { name: 'Завершить звонок' })).not.toHaveAttribute('aria-disabled')
  })

  it('leaves the room when the screen closes', () => {
    const { call, unmount } = renderCall()
    unmount()
    expect(call.disconnected).toBe(true)
  })

  it('publishes the camera and shows its preview for the test blind user', () => {
    const { call } = renderCall({ mode: 'blind' })
    expect(call.options).toEqual({ publishCamera: true })
    expect(call.videos.local).toBe(screen.getByLabelText('Ваша камера'))
  })

  it('works in English', () => {
    const services = createTestServices()
    services.backend.on('GET', '/requests/request-1', errorResponse(404, 'not_found'))
    renderScreen(services, <CallScreen requestId="request-1" credentials={callJson} mode="volunteer" onEnd={vi.fn()} onClosed={vi.fn()} />, 'en')
    expect(screen.getByRole('button', { name: 'End call' })).toBeInTheDocument()
  })
})

describe('callStatusKey', () => {
  it('prefers connection problems over the other person’s state', () => {
    expect(callStatusKey({ ...initialCallState, connection: 'reconnecting', remote: 'present' })).toBe('callReconnecting')
    expect(callStatusKey({ ...initialCallState, connection: 'disconnected', remote: 'left' })).toBe('callDisconnected')
    expect(callStatusKey({ ...initialCallState, connection: 'connected', remote: 'present' })).toBe('callActive')
  })
})
