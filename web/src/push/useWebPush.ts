import { useCallback, useEffect, useRef, useState } from 'react'
import { ErrorCodes, SessionEndedError, errorCode } from '../api/errors'
import type { WebPushSubscription } from '../api/types'
import type { StringKey } from '../i18n'
import { useAnnounce, useServices, useStrings } from '../ui/context'
import { errorKey } from '../ui/errorText'

/**
 * - `loading` — выясняем, что умеет браузер и сервер;
 * - `hidden` — на сервере Web Push не настроен (или сервер недоступен): показывать нечего;
 * - `unsupported`, `needsHomeScreen` — браузер не умеет (на iPhone — пока сайт не на экране «Домой»);
 * - `denied` — уведомления запрещены в настройках браузера;
 * - `off`, `on` — выключены или включены в этом браузере.
 */
export type WebPushStatus = 'loading' | 'hidden' | 'unsupported' | 'needsHomeScreen' | 'denied' | 'off' | 'on'

export type WebPush = {
  status: WebPushStatus
  /** Включение или выключение идёт. */
  busy: boolean
  error: StringKey | null
  enable(): void
  disable(): void
}

/**
 * Уведомления о вызовах в этом браузере (Web Push). Живёт в кабинете волонтёра всё время, пока он открыт,
 * в том числе во время звонка: при выходе подписка этого браузера удаляется, чтобы вызовы
 * не приходили тому, кто вышел.
 */
export function useWebPush(): WebPush {
  const { api, push } = useServices()
  const t = useStrings()
  const announce = useAnnounce()
  const [status, setStatus] = useState<WebPushStatus>('loading')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<StringKey | null>(null)
  const publicKey = useRef<string | null>(null)
  const deviceId = useRef<string | null>(null)

  useEffect(() => {
    let active = true
    void (async () => {
      let next: WebPushStatus = 'hidden'
      try {
        const key = (await api.getPushConfig()).webPushPublicKey
        const support = push.support()
        if (key && support !== 'supported') {
          next = support
        } else if (key) {
          publicKey.current = key
          next = push.permission() === 'denied' ? 'denied' : 'off'
          const subscription = next === 'off' ? await push.current(key) : null
          if (subscription) {
            next = 'on'
            // Каждый раз заново: сервер продлевает запись и закрепляет её за тем, кто сейчас вошёл.
            deviceId.current = await api.registerWebPush(subscription)
          }
        }
      } catch {
        // Нет связи или браузер не дал подключить Service Worker — попробуем при следующем открытии страницы.
      }
      if (active) setStatus(next)
    })()
    return () => {
      active = false
    }
  }, [api, push])

  // Выход: подписку этого браузера — удалить, иначе вызовы приходили бы уже вышедшему волонтёру.
  useEffect(
    () =>
      api.onBeforeLogout(async () => {
        const id = deviceId.current
        deviceId.current = null
        await Promise.allSettled([id ? api.deleteDevice(id) : Promise.resolve(), push.unsubscribe()])
      }),
    [api, push],
  )

  const enable = useCallback(() => {
    const key = publicKey.current
    if (!key || busy) return
    setBusy(true)
    setError(null)
    // subscribe — первым, ещё внутри нажатия: иначе Safari не спросит разрешение.
    const subscribing = push.subscribe(key)
    void (async () => {
      let subscription: WebPushSubscription
      try {
        subscription = await subscribing
      } catch {
        // Пользователь не разрешил уведомления или браузер не подключился к своему push-сервису.
        if (push.permission() === 'denied') {
          setStatus('denied')
          announce(t.pushDenied)
        } else {
          setError(push.isBrave() ? 'pushBraveSettings' : 'pushServiceUnavailable')
        }
        setBusy(false)
        return
      }
      try {
        deviceId.current = await api.registerWebPush(subscription)
        setStatus('on')
        announce(t.pushOnAnnouncement)
      } catch (failure) {
        await push.unsubscribe().catch(() => undefined)
        if (!(failure instanceof SessionEndedError)) {
          // Сервер не знает push-сервис этого браузера (docs/api/openapi.yaml, POST /devices).
          setError(errorCode(failure) === ErrorCodes.invalidRequest ? 'pushBrowserRejected' : errorKey(failure))
        }
      } finally {
        setBusy(false)
      }
    })()
  }, [announce, api, busy, push, t])

  const disable = useCallback(() => {
    if (busy) return
    setBusy(true)
    setError(null)
    void (async () => {
      const id = deviceId.current
      deviceId.current = null
      // С сервера — по возможности; подписка браузера удаляется в любом случае, и сервер узнает об этом сам.
      await Promise.allSettled([id ? api.deleteDevice(id) : Promise.resolve(), push.unsubscribe()])
      setStatus('off')
      setBusy(false)
      announce(t.pushOffAnnouncement)
    })()
  }, [announce, api, busy, push, t])

  return { status, busy, error, enable, disable }
}
