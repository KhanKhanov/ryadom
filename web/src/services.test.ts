import { createBrowserServices } from './services'
import { authJson, jsonResponse, testConfig } from './testing/fakes'

describe('browser services', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('keep working when the browser forbids storage for the site: data lives in memory until reload', async () => {
    // Так браузер отвечает на обращение к хранилищу, если сайту запрещено хранить данные.
    const denied = () => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    }
    vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(denied)
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(denied)
    vi.stubGlobal('fetch', () => Promise.resolve(jsonResponse(200, authJson())))

    const services = createBrowserServices(testConfig)

    services.tabStorage.setItem('key', 'value')
    expect(services.tabStorage.getItem('key')).toBe('value')
    expect(services.api.hasSession()).toBe(false)
    await services.api.loginDev('anna')
    expect(services.api.hasSession()).toBe(true)
  })
})
