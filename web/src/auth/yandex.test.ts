import { MemoryStorage } from '../testing/fakes'
import { codeChallenge, randomUrlSafeString, readYandexCallback, startYandexLogin, withoutCallbackParams } from './yandex'

describe('PKCE', () => {
  it('computes the S256 code challenge (RFC 7636, appendix B)', async () => {
    expect(await codeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  })

  it('generates a URL-safe verifier of 43 characters from 32 random bytes', () => {
    const verifier = randomUrlSafeString(32)
    expect(verifier).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(randomUrlSafeString(32)).not.toBe(verifier)
  })
})

describe('Yandex ID login', () => {
  it('builds the authorization URL and remembers the PKCE data in this tab', async () => {
    const storage = new MemoryStorage()

    const url = new URL(await startYandexLogin('client-123', 'http://localhost:5173/', storage))

    expect(url.origin + url.pathname).toBe('https://oauth.yandex.ru/authorize')
    expect(url.searchParams.get('response_type')).toBe('code')
    expect(url.searchParams.get('client_id')).toBe('client-123')
    expect(url.searchParams.get('redirect_uri')).toBe('http://localhost:5173/')
    expect(url.searchParams.get('code_challenge_method')).toBe('S256')
    const pending = JSON.parse(storage.getItem('ryadom.yandexLogin')!)
    expect(url.searchParams.get('state')).toBe(pending.state)
    expect(url.searchParams.get('code_challenge')).toBe(await codeChallenge(pending.codeVerifier))
  })

  it('returns the code with the verifier when Yandex sends the user back', async () => {
    const storage = new MemoryStorage()
    const authorize = new URL(await startYandexLogin('client-123', 'http://localhost:5173/', storage))
    const state = authorize.searchParams.get('state')
    const { codeVerifier } = JSON.parse(storage.getItem('ryadom.yandexLogin')!)

    const callback = readYandexCallback(new URL(`http://localhost:5173/?code=abc&state=${state}`), storage)

    expect(callback).toEqual({ kind: 'code', code: 'abc', codeVerifier })
    // Данные входа одноразовые.
    expect(storage.getItem('ryadom.yandexLogin')).toBeNull()
  })

  it('rejects a response that this tab did not ask for', async () => {
    const storage = new MemoryStorage()
    await startYandexLogin('client-123', 'http://localhost:5173/', storage)

    expect(readYandexCallback(new URL('http://localhost:5173/?code=abc&state=forged'), storage)).toEqual({ kind: 'error', error: 'failed' })
    expect(readYandexCallback(new URL('http://localhost:5173/?code=abc&state=any'), new MemoryStorage())).toEqual({
      kind: 'error',
      error: 'failed',
    })
  })

  it('recognizes a cancelled login', async () => {
    const storage = new MemoryStorage()
    const state = new URL(await startYandexLogin('client-123', 'http://localhost:5173/', storage)).searchParams.get('state')

    expect(readYandexCallback(new URL(`http://localhost:5173/?error=access_denied&state=${state}`), storage)).toEqual({
      kind: 'error',
      error: 'denied',
    })
  })

  it('ignores ordinary page visits', () => {
    expect(readYandexCallback(new URL('http://localhost:5173/'), new MemoryStorage())).toBeNull()
  })

  it('removes the Yandex response from the address', () => {
    expect(withoutCallbackParams(new URL('http://localhost:5173/?code=abc&state=s&cid=1&lang=en#top'))).toBe('/?lang=en#top')
  })
})
