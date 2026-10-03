import { act, screen, within } from '@testing-library/react'
import { parseHelpRequest } from '../api/types'
import { callJson, createTestServices, errorResponse, jsonResponse, requestJson } from '../testing/fakes'
import { announced, renderScreen } from '../testing/render'
import { DevBlindScreen } from './DevBlindScreen'

function renderBlindPage() {
  const services = createTestServices({ loggedIn: true, config: { devTools: true } })
  return { services, ...renderScreen(services, <DevBlindScreen />) }
}

/** Видимый экран — без невидимой области для экранного диктора, где повторяется тот же текст. */
const main = () => within(document.querySelector('section')!)

describe('DevBlindScreen', () => {
  it('asks for help and cancels the search', async () => {
    const { services, user } = renderBlindPage()
    services.backend
      .on('POST', '/requests', jsonResponse(201, requestJson()))
      .on('DELETE', '/requests/request-1', jsonResponse(200, requestJson({ status: 'cancelled' })))

    await user.click(screen.getByRole('button', { name: 'Попросить помощи' }))
    expect(await screen.findByRole('button', { name: 'Отменить' })).toBeInTheDocument()
    expect(announced()).toBe('Ищем волонтёра…')

    await user.click(screen.getByRole('button', { name: 'Отменить' }))

    expect(await main().findByText('Запрос отменён.')).toBeInTheDocument()
    expect(services.backend.calls('POST', '/requests')[0].body).toEqual({})
  })

  it('joins the call with the camera when a volunteer accepts', async () => {
    const { services, user } = renderBlindPage()
    services.backend
      .on('POST', '/requests', jsonResponse(201, requestJson()))
      .on('DELETE', '/requests/request-1', jsonResponse(200, requestJson({ status: 'ended' })))
    await user.click(screen.getByRole('button', { name: 'Попросить помощи' }))
    await screen.findByRole('button', { name: 'Отменить' })

    act(() => services.realtime.emit('request.accepted', parseHelpRequest(requestJson({ status: 'accepted', call: callJson }))))

    expect(screen.getByRole('heading', { name: 'Звонок' })).toBeInTheDocument()
    expect(services.calls.last.options).toEqual({ publishCamera: true })

    await user.click(screen.getByRole('button', { name: 'Завершить звонок' }))

    expect(await main().findByText('Звонок завершён')).toBeInTheDocument()
    expect(services.calls.last.disconnected).toBe(true)
  })

  it('says when nobody answered', async () => {
    const { services, user } = renderBlindPage()
    services.backend.on('POST', '/requests', jsonResponse(201, requestJson()))
    await user.click(screen.getByRole('button', { name: 'Попросить помощи' }))
    await screen.findByRole('button', { name: 'Отменить' })

    act(() => services.realtime.emit('request.no_answer', parseHelpRequest(requestJson({ status: 'no_answer' }))))

    expect(main().getByText('Сейчас никто не ответил. Попробуйте ещё раз.')).toBeInTheDocument()
  })

  it('picks up an active request after reconnecting', async () => {
    const { services } = renderBlindPage()
    services.backend.on('GET', '/requests/current', jsonResponse(200, requestJson({ status: 'in_call', call: callJson })))

    act(() => services.realtime.ready())

    expect(await screen.findByRole('heading', { name: 'Звонок' })).toBeInTheDocument()
  })

  it('shows a limit error', async () => {
    const { services, user } = renderBlindPage()
    services.backend.on('POST', '/requests', errorResponse(429, 'too_many_requests'))

    await user.click(screen.getByRole('button', { name: 'Попросить помощи' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Слишком много запросов.')
  })
})
