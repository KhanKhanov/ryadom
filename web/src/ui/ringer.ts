// Звук входящего вызова. Синтезируется в браузере (Web Audio API) — без звуковых файлов.

export interface Ringer {
  /** Играть сигнал вызова, пока не вызван stop(). */
  start(): void
  stop(): void
  /** Один короткий сигнал — проверить, что звук слышен. Вызывать по нажатию кнопки. */
  test(): void
}

/** Пауза между повторами сигнала вызова. */
const RING_INTERVAL_MS = 3_000

/**
 * Браузеры разрешают звук только после действия пользователя на странице. Поэтому звук готовится
 * при любом нажатии; если после загрузки страницы нажатий не было, вызов придёт без звука —
 * для этого на странице есть кнопка «Проверить звук».
 */
export function createRinger(doc: Document = document): Ringer {
  let context: AudioContext | null = null
  let timer: ReturnType<typeof setInterval> | undefined

  const audio = (): AudioContext | null => {
    if (typeof AudioContext === 'undefined') return null
    context ??= new AudioContext()
    if (context.state === 'suspended') context.resume().catch(() => undefined)
    return context
  }
  const unlock = () => void audio()
  doc.addEventListener('pointerdown', unlock, { capture: true })
  doc.addEventListener('keydown', unlock, { capture: true })

  const ring = () => {
    const ctx = audio()
    if (!ctx) return
    // Два тона, как у телефонного звонка.
    beep(ctx, 880, ctx.currentTime, 0.3)
    beep(ctx, 660, ctx.currentTime + 0.4, 0.3)
  }

  return {
    start() {
      if (timer !== undefined) return
      ring()
      timer = setInterval(ring, RING_INTERVAL_MS)
    },
    stop() {
      clearInterval(timer)
      timer = undefined
    },
    test() {
      const ctx = audio()
      if (ctx) beep(ctx, 880, ctx.currentTime, 0.3)
    },
  }
}

function beep(ctx: AudioContext, frequency: number, startAt: number, duration: number) {
  const oscillator = ctx.createOscillator()
  const gain = ctx.createGain()
  oscillator.frequency.value = frequency
  // Плавные начало и конец — без щелчков.
  gain.gain.setValueAtTime(0, startAt)
  gain.gain.linearRampToValueAtTime(0.3, startAt + 0.02)
  gain.gain.setValueAtTime(0.3, startAt + duration - 0.05)
  gain.gain.linearRampToValueAtTime(0, startAt + duration)
  oscillator.connect(gain).connect(ctx.destination)
  oscillator.start(startAt)
  oscillator.stop(startAt + duration)
}
