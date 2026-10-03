import { screen, waitFor, within } from '@testing-library/react'
import { SOURCE_CODE_URL } from './config'
import { applyLanguageToDocument, detectLanguage, format } from './i18n'
import { authJson, createTestServices, errorResponse, jsonResponse, profileJson } from './testing/fakes'
import { renderApp } from './testing/render'

describe('App', () => {
  it('shows the title as a heading in Russian', () => {
    renderApp(createTestServices())
    expect(screen.getByRole('heading', { level: 1, name: 'Рядом' })).toBeInTheDocument()
  })

  it('shows the title in English', () => {
    renderApp(createTestServices(), { language: 'en' })
    expect(screen.getByRole('heading', { level: 1, name: 'Ryadom' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Volunteer sign-in' })).toBeInTheDocument()
  })

  it('links to the source code in the footer, as AGPL requires', () => {
    renderApp(createTestServices())
    const footer = screen.getByRole('contentinfo')
    const link = within(footer).getByRole('link', { name: 'Исходный код' })
    expect(link).toHaveAttribute('href', SOURCE_CODE_URL)
  })

  it('translates the source code link', () => {
    renderApp(createTestServices(), { language: 'en' })
    expect(screen.getByRole('link', { name: 'Source code' })).toHaveAttribute('href', SOURCE_CODE_URL)
  })
})

describe('login', () => {
  it('signs in without OAuth in development and becomes a volunteer', async () => {
    const services = createTestServices({ config: { devTools: true } })
    services.backend
      .on('POST', '/auth/dev', jsonResponse(200, authJson({ user: { role: null, displayName: 'volunteer-1' } })))
      .on('PATCH', '/me', jsonResponse(200, profileJson({ displayName: 'volunteer-1' })))
      .on('GET', '/requests/current', jsonResponse(204))
    const { user } = renderApp(services)

    await user.type(screen.getByLabelText('Логин'), 'volunteer-1')
    await user.click(screen.getByRole('button', { name: 'Войти' }))
    await user.click(await screen.findByRole('button', { name: 'Стать волонтёром' }))

    expect(await screen.findByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    expect(services.backend.calls('POST', '/auth/dev')[0].body).toEqual({ login: 'volunteer-1' })
    // Вместе с ролью сохраняется часовой пояс браузера — по нему сервер считает время тишины.
    expect(services.backend.calls('PATCH', '/me')[0].body).toEqual({
      role: 'volunteer',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    })
    expect(screen.getByText('Вы вошли как volunteer-1')).toBeInTheDocument()
  })

  it('saves the role without the time zone if the server does not know it', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend
      .on('GET', '/me', jsonResponse(200, profileJson({ role: null })))
      .on('PATCH', '/me', (request) =>
        (request.body as Record<string, unknown>).timezone ? errorResponse(400, 'invalid_request') : jsonResponse(200, profileJson()),
      )
      .on('GET', '/requests/current', jsonResponse(204))
    const { user } = renderApp(services)

    await user.click(await screen.findByRole('button', { name: 'Стать волонтёром' }))

    expect(await screen.findByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    expect(services.backend.calls('PATCH', '/me').at(-1)?.body).toEqual({ role: 'volunteer' })
  })

  it('checks the development login before sending it', async () => {
    const { user } = renderApp(createTestServices({ config: { devTools: true } }))

    await user.type(screen.getByLabelText('Логин'), 'Иван')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Логин может содержать только')
  })

  it('explains when development login is disabled on the server', async () => {
    const services = createTestServices({ config: { devTools: true } })
    const { user } = renderApp(services)

    await user.type(screen.getByLabelText('Логин'), 'volunteer-1')
    await user.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Этот способ входа не включён на сервере.')
  })

  it('has no development login in production', () => {
    renderApp(createTestServices())
    expect(screen.queryByLabelText('Логин')).not.toBeInTheDocument()
    expect(screen.getByText('Вход пока не настроен на этом сервере.')).toBeInTheDocument()
  })

  it('sends the user to Yandex ID', async () => {
    const services = createTestServices({ config: { yandexClientId: 'client-123' } })
    const { user } = renderApp(services)

    await user.click(screen.getByRole('button', { name: 'Войти через Яндекс ID' }))

    await waitFor(() => expect(services.navigate).toHaveBeenCalledOnce())
    const url = new URL(services.navigate.mock.calls[0][0] as string)
    expect(url.hostname).toBe('oauth.yandex.ru')
    expect(url.searchParams.get('client_id')).toBe('client-123')
  })

  it('finishes Yandex ID login when the user comes back', async () => {
    const services = createTestServices()
    services.backend
      .on('POST', '/auth/oauth/yandex', jsonResponse(200, authJson()))
      .on('GET', '/requests/current', jsonResponse(204))

    renderApp(services, { yandexCallback: { kind: 'code', code: 'abc', codeVerifier: 'v'.repeat(43) } })

    expect(await screen.findByRole('heading', { name: 'Кабинет волонтёра' })).toBeInTheDocument()
    expect(services.backend.calls('POST', '/auth/oauth/yandex')).toHaveLength(1)
  })

  it('explains a cancelled Yandex ID login', () => {
    renderApp(createTestServices({ config: { yandexClientId: 'client-123' } }), {
      yandexCallback: { kind: 'error', error: 'denied' },
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Вход через Яндекс ID отменён.')
  })
})

describe('session', () => {
  it('restores the saved login and signs out', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend
      .on('GET', '/me', jsonResponse(200, profileJson()))
      .on('GET', '/requests/current', jsonResponse(204))
      .on('POST', '/auth/logout', jsonResponse(204))
    const { user } = renderApp(services)

    await user.click(await screen.findByRole('button', { name: 'Выйти' }))

    expect(await screen.findByRole('heading', { name: 'Вход для волонтёров' })).toBeInTheDocument()
    expect(services.storage.length).toBe(0)
    expect(services.realtime.stopped).toBe(1)
  })

  it('asks to sign in again when the session has expired', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend.on('GET', '/me', errorResponse(401, 'unauthorized')).on('POST', '/auth/refresh', errorResponse(401, 'invalid_refresh_token'))

    renderApp(services)

    expect(await screen.findByRole('alert')).toHaveTextContent('Сеанс истёк. Войдите снова.')
  })

  it('offers to try again when the server is unreachable', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend.on('GET', '/me', () => new Response(null, { status: 502 }))
    const { user } = renderApp(services)

    expect(await screen.findByRole('heading', { name: 'Нет связи с сервером' })).toBeInTheDocument()

    services.backend.on('GET', '/me', jsonResponse(200, profileJson({ role: 'admin' })))
    await user.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByRole('heading', { name: 'Админка появится позже' })).toBeInTheDocument()
  })
})

describe('roles', () => {
  it('tells a blind user that the web version is for volunteers', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend
      .on('GET', '/me', jsonResponse(200, profileJson({ role: 'blind' })))
      .on('PATCH', '/me', errorResponse(409, 'active_request_exists'))
    const { user } = renderApp(services)

    await user.click(await screen.findByRole('button', { name: 'Стать волонтёром' }))

    expect(screen.getByRole('heading', { name: 'Этот аккаунт — для просьб о помощи' })).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toHaveTextContent('Сначала завершите текущий запрос или звонок.')
  })

  it('opens the test blind page for a blind user in development', async () => {
    const services = createTestServices({ loggedIn: true, config: { devTools: true } })
    services.backend.on('GET', '/me', jsonResponse(200, profileJson({ role: 'blind' })))

    renderApp(services)

    expect(await screen.findByRole('heading', { name: 'Тестовый незрячий' })).toBeInTheDocument()
  })

  it('does not break on a role from a newer server', async () => {
    const services = createTestServices({ loggedIn: true })
    services.backend.on('GET', '/me', jsonResponse(200, profileJson({ role: 'moderator' })))

    renderApp(services)

    expect(await screen.findByRole('heading', { name: 'Веб-версия не поддерживает вашу роль' })).toBeInTheDocument()
  })
})

describe('i18n', () => {
  it('picks the first supported language', () => {
    expect(detectLanguage(['de-DE', 'en-US', 'ru'])).toBe('en')
  })

  it('falls back to Russian', () => {
    expect(detectLanguage(['de-DE'])).toBe('ru')
    expect(detectLanguage([])).toBe('ru')
  })

  it('sets the page language and a translated title', () => {
    applyLanguageToDocument(document, 'en')
    expect(document.documentElement.lang).toBe('en')
    expect(document.title).toBe('Ryadom')

    applyLanguageToDocument(document, 'ru')
    expect(document.documentElement.lang).toBe('ru')
    expect(document.title).toBe('Рядом')
  })

  it('fills placeholders', () => {
    expect(format('С {from} до {to}', { from: '22:00', to: '08:00' })).toBe('С 22:00 до 08:00')
    expect(format('{missing}', {})).toBe('{missing}')
  })
})
