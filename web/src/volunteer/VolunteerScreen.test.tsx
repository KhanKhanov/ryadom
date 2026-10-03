import { act, screen, waitFor, within } from '@testing-library/react'
import { parseHelpRequest, type HelpRequest } from '../api/types'
import { callJson, createTestServices, errorResponse, jsonResponse, profileJson, requestJson, type TestServices } from '../testing/fakes'
import { announced, renderApp } from '../testing/render'

/** Волонтёр уже входил; сервер отвечает на профиль и текущий запрос. */
async function openVolunteerPage(options: { profile?: Record<string, unknown>; now?: Date; current?: Response } = {}) {
  const services = createTestServices({ loggedIn: true, now: options.now })
  services.backend
    .on('GET', '/me', jsonResponse(200, profileJson(options.profile)))
    .on('GET', '/requests/current', options.current ?? jsonResponse(204))
  const rendered = renderApp(services)
  await screen.findByRole('heading', { name: 'Кабинет волонтёра' })
  // Заголовок появляется чуть раньше, чем экран открывает соединение событий (эффект React).
  await waitFor(() => expect(services.realtime.connections).toBeGreaterThan(0))
  return { services, ...rendered }
}

function incoming(services: TestServices, overrides: Record<string, unknown> = {}) {
  act(() => services.realtime.emit('request.incoming', helpRequest(overrides)))
}

function helpRequest(overrides: Record<string, unknown> = {}): HelpRequest {
  return parseHelpRequest(requestJson(overrides))
}

/** Страница открыта во время звонка; [remoteJoined] — собеседник уже в комнате. */
async function openCall(options: { remoteJoined: boolean }) {
  const page = await openVolunteerPage({ current: jsonResponse(200, requestJson({ status: 'in_call', call: callJson })) })
  act(() => page.services.realtime.ready())
  await screen.findByRole('heading', { name: 'Звонок' })
  const call = page.services.calls.last
  act(() => call.update({ connection: 'connected', remote: options.remoteJoined ? 'present' : 'waiting' }))
  return { ...page, call }
}

describe('volunteer page', () => {
  it('turns calls off and on', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('PATCH', '/me', (request) =>
      jsonResponse(200, profileJson({ notificationsEnabled: (request.body as { notificationsEnabled: boolean }).notificationsEnabled })),
    )
    const toggle = screen.getByRole('switch', { name: 'Готов помогать' })
    expect(toggle).toBeChecked()

    await user.click(toggle)

    await waitFor(() => expect(toggle).not.toBeChecked())
    expect(services.backend.calls('PATCH', '/me')[0].body).toEqual({ notificationsEnabled: false })
    expect(screen.getByText('Вызовы не приходят. Включите, когда будете готовы помочь.')).toBeInTheDocument()
    expect(announced()).toBe('Вызовы выключены')
  })

  it('shows the connection to the server in words', async () => {
    const { services } = await openVolunteerPage()
    const main = screen.getByRole('main')
    expect(within(main).getByText('Подключаемся к серверу…')).toBeInTheDocument()

    act(() => services.realtime.ready())
    expect(within(main).getByText('На связи с сервером.')).toBeInTheDocument()

    act(() => services.realtime.status('reconnecting'))
    expect(within(main).getByText('Нет связи с сервером. Переподключаемся…')).toBeInTheDocument()
  })

  it('announces when the connection to the server is lost and back, but not the first connection', async () => {
    const { services } = await openVolunteerPage()
    act(() => services.realtime.ready())
    expect(announced()).toBe('')

    act(() => services.realtime.status('reconnecting'))
    expect(announced()).toBe('Нет связи с сервером. Переподключаемся…')

    act(() => services.realtime.ready())
    expect(announced()).toBe('На связи с сервером.')
  })

  it('rings and announces an incoming call until it is skipped', async () => {
    const { services, user } = await openVolunteerPage()

    incoming(services, { language: 'en' })

    const card = screen.getByRole('listitem')
    expect(within(card).getByText('Нужна помощь')).toBeInTheDocument()
    expect(within(card).getByText('Язык: английский')).toBeInTheDocument()
    expect(announced('assertive')).toBe('Входящий вызов: нужна помощь')
    expect(services.ringer.start).toHaveBeenCalledOnce()
    expect(document.title).toBe('Вызов! — Рядом')

    await user.click(within(card).getByRole('button', { name: 'Пропустить' }))

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
    expect(services.ringer.stop).toHaveBeenCalledOnce()
    expect(document.title).toBe('Рядом')
  })

  it('removes a call that another volunteer accepted', async () => {
    const { services } = await openVolunteerPage()
    incoming(services)

    act(() => services.realtime.emit('request.taken', helpRequest({ status: 'accepted' })))

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByText('Вызов принял другой волонтёр.')).toBeInTheDocument()
    expect(announced()).toBe('Вызов принял другой волонтёр.')
  })

  it('stops ringing when the volunteer accepted the call in another tab', async () => {
    const { services } = await openVolunteerPage()
    incoming(services)

    act(() => services.realtime.emit('request.accepted', helpRequest({ status: 'accepted' })))

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
    expect(services.ringer.stop).toHaveBeenCalled()
    expect(announced()).toBe('Вы приняли этот вызов в другой вкладке или на другом устройстве.')
  })

  it('accepts a call and joins the video call with the microphone only', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('POST', '/requests/request-1/accept', jsonResponse(200, requestJson({ status: 'accepted', call: callJson })))
    incoming(services)

    await user.click(screen.getByRole('button', { name: 'Принять' }))

    expect(await screen.findByRole('heading', { name: 'Звонок' })).toBeInTheDocument()
    const call = services.calls.last
    expect(call.credentials).toEqual(callJson)
    expect(call.options).toEqual({ publishCamera: false })
    expect(call.connected).toBe(true)
    expect(call.videos.remote).toBeInstanceOf(HTMLVideoElement)
    expect(screen.getByLabelText('Видео с камеры собеседника')).toBeInTheDocument()
    expect(services.ringer.stop).toHaveBeenCalled()
  })

  it('explains when another volunteer was faster', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('POST', '/requests/request-1/accept', errorResponse(409, 'request_taken'))
    incoming(services)

    await user.click(screen.getByRole('button', { name: 'Принять' }))

    expect(await within(screen.getByRole('main')).findByText('Этот вызов уже принял другой волонтёр.')).toBeInTheDocument()
    expect(announced()).toBe('Этот вызов уже принял другой волонтёр.')
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
  })

  it('shows an unknown error code as a general message', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('POST', '/requests/request-1/accept', errorResponse(409, 'volunteer_on_break'))
    incoming(services)

    await user.click(screen.getByRole('button', { name: 'Принять' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Что-то пошло не так. Попробуйте ещё раз.')
    expect(screen.getByRole('listitem')).toBeInTheDocument()
  })

  it('ends the call, leaves the room and asks whether it helped', async () => {
    const { services, user, call } = await openCall({ remoteJoined: true })
    services.backend
      .on('DELETE', '/requests/request-1', jsonResponse(200, requestJson({ status: 'ended' })))
      .on('POST', '/requests/request-1/rating', jsonResponse(204))

    await user.click(screen.getByRole('button', { name: 'Завершить звонок' }))

    expect(await screen.findByRole('heading', { name: 'Звонок завершён' })).toBeInTheDocument()
    expect(call.disconnected).toBe(true)
    expect(screen.queryByText('Собеседник завершил звонок.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Да' }))

    expect(await screen.findByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    expect(services.backend.calls('POST', '/requests/request-1/rating')[0].body).toEqual({ helped: true })
    expect(announced()).toBe('Спасибо!')
  })

  it('does not say the other person ended the call when the server event comes before the answer', async () => {
    const { services, user } = await openCall({ remoteJoined: true })
    let answer: (response: Response) => void = () => undefined
    services.backend.on('DELETE', '/requests/request-1', () => new Promise<Response>((resolve) => (answer = resolve)))

    await user.click(screen.getByRole('button', { name: 'Завершить звонок' }))
    act(() => services.realtime.emit('request.ended', helpRequest({ status: 'ended' })))
    await act(async () => answer(jsonResponse(200, requestJson({ status: 'ended' }))))

    expect(screen.getByRole('heading', { name: 'Звонок завершён' })).toBeInTheDocument()
    expect(screen.queryByText('Собеседник завершил звонок.')).not.toBeInTheDocument()
    expect(announced()).not.toBe('Собеседник завершил звонок.')
  })

  it('does not ask for a rating when the call did not take place', async () => {
    const { services } = await openCall({ remoteJoined: false })

    act(() => services.realtime.emit('request.cancelled', helpRequest({ status: 'cancelled' })))

    expect(screen.getByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    expect(screen.queryByText('Удалось помочь?')).not.toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByText('Звонок не состоялся: собеседник не подключился.')).toBeInTheDocument()
    expect(announced()).toBe('Звонок не состоялся: собеседник не подключился.')
    expect(services.calls.last.disconnected).toBe(true)
  })

  it('ends the call before signing out: otherwise it would stay open on the server', async () => {
    const { services, user } = await openCall({ remoteJoined: true })
    services.backend
      .on('DELETE', '/requests/request-1', jsonResponse(200, requestJson({ status: 'ended' })))
      .on('POST', '/auth/logout', jsonResponse(204))

    await user.click(screen.getByRole('button', { name: 'Выйти' }))

    expect(await screen.findByRole('heading', { name: 'Вход для волонтёров' })).toBeInTheDocument()
    const order = services.backend.requests.map((r) => `${r.method} ${r.path}`)
    expect(order.indexOf('DELETE /requests/request-1')).toBeGreaterThanOrEqual(0)
    expect(order.indexOf('DELETE /requests/request-1')).toBeLessThan(order.indexOf('POST /auth/logout'))
    expect(services.backend.calls('DELETE', '/requests/request-1')[0].authorization).toBe('Bearer access-1')
  })

  it('signs out even if the call cannot be ended: there is no connection', async () => {
    const { services, user } = await openCall({ remoteJoined: true })
    services.backend.on('DELETE', '/requests/request-1', () => Promise.reject(new TypeError('Failed to fetch')))

    await user.click(screen.getByRole('button', { name: 'Выйти' }))

    expect(await screen.findByRole('heading', { name: 'Вход для волонтёров' })).toBeInTheDocument()
  })

  it('leaves the call when the other person ends it', async () => {
    const { services, user } = await openCall({ remoteJoined: true })

    act(() => services.realtime.emit('request.ended', helpRequest({ status: 'ended' })))

    expect(screen.getByRole('heading', { name: 'Звонок завершён' })).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByText('Собеседник завершил звонок.')).toBeInTheDocument()
    expect(announced()).toBe('Собеседник завершил звонок.')
    expect(services.calls.last.disconnected).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Пропустить' }))
    expect(screen.getByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    // Сообщение о конце звонка осталось на прошлом экране и не путается с сообщениями о вызовах.
    expect(within(screen.getByRole('main')).queryByText('Собеседник завершил звонок.')).not.toBeInTheDocument()
  })

  it('rechecks incoming calls after reconnecting: events without a connection are not repeated', async () => {
    const { services } = await openVolunteerPage()
    services.backend.on('GET', '/requests/request-1', jsonResponse(200, requestJson({ status: 'no_answer' })))
    incoming(services)

    act(() => services.realtime.ready())

    await waitFor(() => expect(screen.queryByRole('listitem')).not.toBeInTheDocument())
  })

  it('shows quiet hours and says when calls will not come', async () => {
    // 23:30 по Москве.
    await openVolunteerPage({ now: new Date('2026-10-03T20:30:00Z') })

    expect(screen.getByText('С 22:00 до 08:00 по часовому поясу Europe/Moscow вызовы не приходят.')).toBeInTheDocument()
    expect(screen.getByText('Сейчас время тишины: вызовы не придут до 08:00.')).toBeInTheDocument()
  })

  it('turns quiet hours off', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('PATCH', '/me', jsonResponse(200, profileJson({ doNotDisturb: { from: '00:00', to: '00:00' } })))

    await user.click(screen.getByRole('button', { name: 'Изменить время тишины' }))
    expect(screen.getByLabelText('Начало')).toHaveValue('22:00')
    await user.click(screen.getByRole('checkbox', { name: 'Принимать вызовы круглосуточно' }))
    await user.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Вызовы приходят в любое время суток.')).toBeInTheDocument()
    expect(services.backend.calls('PATCH', '/me')[0].body).toEqual({ doNotDisturb: { from: '00:00', to: '00:00' } })
  })

  it('changes quiet hours', async () => {
    const { services, user } = await openVolunteerPage()
    services.backend.on('PATCH', '/me', jsonResponse(200, profileJson({ doNotDisturb: { from: '23:00', to: '07:30' } })))

    await user.click(screen.getByRole('button', { name: 'Изменить время тишины' }))
    await user.clear(screen.getByLabelText('Начало'))
    await user.type(screen.getByLabelText('Начало'), '23:00')
    await user.clear(screen.getByLabelText('Конец'))
    await user.type(screen.getByLabelText('Конец'), '07:30')
    await user.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('С 23:00 до 07:30 по часовому поясу Europe/Moscow вызовы не приходят.')).toBeInTheDocument()
    expect(services.backend.calls('PATCH', '/me')[0].body).toEqual({ doNotDisturb: { from: '23:00', to: '07:30' } })
  })

  it('plays a test sound on request', async () => {
    const { services, user } = await openVolunteerPage()
    await user.click(screen.getByRole('button', { name: 'Проверить звук' }))
    expect(services.ringer.test).toHaveBeenCalledOnce()
  })
})
