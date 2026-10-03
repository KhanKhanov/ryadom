import { MANIFEST_LANGUAGES, manifestPath, webManifest } from './manifest'

describe('web app manifest', () => {
  it('exists in every language of the site', () => {
    expect(MANIFEST_LANGUAGES).toEqual(['ru', 'en'])
    expect(manifestPath('en')).toBe('/manifest-en.webmanifest')
  })

  it('opens as a standalone app: only such a site gets push notifications on iPhone', () => {
    const manifest = JSON.parse(webManifest('ru'))

    expect(manifest).toMatchObject({ name: 'Рядом', lang: 'ru', start_url: '/', display: 'standalone' })
    expect(manifest.icons.map((icon: { sizes: string }) => icon.sizes)).toContain('512x512')
    expect(JSON.parse(webManifest('en')).name).toBe('Ryadom')
  })
})
