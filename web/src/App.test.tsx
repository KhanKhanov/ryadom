import { render, screen, within } from '@testing-library/react'
import { App } from './App'
import { SOURCE_CODE_URL } from './config'
import { applyLanguageToDocument, detectLanguage } from './i18n'

describe('App', () => {
  it('shows the title as a heading in Russian', () => {
    render(<App language="ru" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Рядом' })).toBeInTheDocument()
  })

  it('shows the title in English', () => {
    render(<App language="en" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Ryadom' })).toBeInTheDocument()
  })

  it('links to the source code in the footer, as AGPL requires', () => {
    render(<App language="ru" />)
    const footer = screen.getByRole('contentinfo')
    const link = within(footer).getByRole('link', { name: 'Исходный код' })
    expect(link).toHaveAttribute('href', SOURCE_CODE_URL)
  })

  it('translates the source code link', () => {
    render(<App language="en" />)
    expect(screen.getByRole('link', { name: 'Source code' })).toHaveAttribute('href', SOURCE_CODE_URL)
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

describe('applyLanguageToDocument', () => {
  it('sets the page language and a translated title', () => {
    applyLanguageToDocument(document, 'en')
    expect(document.documentElement.lang).toBe('en')
    expect(document.title).toBe('Ryadom')

    applyLanguageToDocument(document, 'ru')
    expect(document.documentElement.lang).toBe('ru')
    expect(document.title).toBe('Рядом')
  })
})
