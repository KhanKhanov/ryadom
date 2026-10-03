// Небольшие общие компоненты с правилами доступности.

import { useEffect, useRef, type ButtonHTMLAttributes, type MouseEvent, type ReactNode } from 'react'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  /** Действие выполняется: кнопка не реагирует, но остаётся в фокусе. */
  busy?: boolean
  variant?: 'primary' | 'secondary' | 'danger'
}

/**
 * Кнопка размером не меньше 48×48 px. Вместо `disabled` — `aria-disabled`: отключённая кнопка
 * теряет фокус, и пользователь клавиатуры или экранного диктора «выпадает» в начало страницы.
 */
export function Button({ busy, disabled, variant = 'secondary', className, onClick, type = 'button', children, ...rest }: ButtonProps) {
  const inactive = Boolean(busy || disabled)
  const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
    if (inactive) {
      event.preventDefault()
      return
    }
    onClick?.(event)
  }
  return (
    <button
      {...rest}
      type={type}
      className={['button', `button-${variant}`, className].filter(Boolean).join(' ')}
      aria-disabled={inactive || undefined}
      aria-busy={busy || undefined}
      onClick={handleClick}
    >
      {children}
    </button>
  )
}

/** Показан ли уже хотя бы один экран: на первом экране после загрузки фокус не переносим. */
let screenShown = false

/**
 * Заголовок экрана. Когда экран сменился, фокус переходит на заголовок: пользователь экранного
 * диктора сразу слышит, где он, а пользователь клавиатуры продолжает отсюда, а не с начала страницы.
 */
export function ScreenHeading({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    if (screenShown) ref.current?.focus()
    screenShown = true
  }, [])
  return (
    <h2 ref={ref} tabIndex={-1} className="screen-heading">
      {children}
    </h2>
  )
}

/** Ошибка рядом с действием. role="alert" — диктор прочитает её сразу. */
export function ErrorMessage({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="error">
      {children}
    </p>
  )
}
