import { render, screen } from '@testing-library/react'
import { App } from './App'
import { detectLanguage } from './i18n'

describe('App', () => {
  it('shows the title as a heading in Russian', () => {
    render(<App language="ru" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Рядом' })).toBeInTheDocument()
  })

  it('shows the title in English', () => {
    render(<App language="en" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Ryadom' })).toBeInTheDocument()
  })
})

describe('detectLanguage', () => {
  it('picks the first supported language', () => {
    expect(detectLanguage(['de-DE', 'en-US', 'ru'])).toBe('en')
  })

  it('falls back to Russian', () => {
    expect(detectLanguage(['de-DE'])).toBe('ru')
    expect(detectLanguage([])).toBe('ru')
  })
})
