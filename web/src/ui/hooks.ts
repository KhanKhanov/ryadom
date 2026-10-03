import { useEffect, useRef, useState } from 'react'
import type { RealtimeHandlers, RealtimeStatus } from '../api/realtime'
import { useServices } from './context'

type Handlers = Pick<RealtimeHandlers, 'onReady' | 'onEvent'>

/**
 * Держит открытым WebSocket событий, пока экран показан. Возвращает состояние связи.
 * Обработчики можно передавать новые при каждой отрисовке — соединение из-за этого не переоткрывается.
 */
export function useRealtime(handlers: Handlers): RealtimeStatus {
  const { connectRealtime } = useServices()
  const [status, setStatus] = useState<RealtimeStatus>('connecting')
  const latest = useRef(handlers)
  useEffect(() => {
    latest.current = handlers
  })
  useEffect(() => {
    const connection = connectRealtime({
      onReady: () => latest.current.onReady(),
      onEvent: (event) => latest.current.onEvent(event),
      onStatus: setStatus,
    })
    return () => connection.stop()
  }, [connectRealtime])
  return status
}

/** Последнее значение [value] для обработчиков событий, которые не должны пересоздаваться. */
export function useLatest<T>(value: T): { readonly current: T } {
  const ref = useRef(value)
  useEffect(() => {
    ref.current = value
  })
  return ref
}

/** Текущее время, обновляется раз в [intervalMs]. */
export function useNow(intervalMs = 60_000): Date {
  const { now } = useServices()
  const [time, setTime] = useState(now)
  useEffect(() => {
    const timer = setInterval(() => setTime(now()), intervalMs)
    return () => clearInterval(timer)
  }, [now, intervalMs])
  return time
}
