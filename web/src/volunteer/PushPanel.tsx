import type { WebPush } from '../push/useWebPush'
import { Button, ErrorMessage } from '../ui/components'
import { useStrings } from '../ui/context'

/** «Уведомления о вызовах»: включить и выключить Web Push или объяснить, почему их нет. */
export function PushPanel({ push }: { push: WebPush }) {
  const t = useStrings()
  // Пока не ясно, что умеют браузер и сервер, или Web Push на сервере не настроен, — панели нет.
  if (push.status === 'loading' || push.status === 'hidden') return null
  return (
    <section className="panel" aria-labelledby="push-title">
      <h3 id="push-title">{t.pushTitle}</h3>
      {push.status === 'unsupported' && <p>{t.pushUnsupported}</p>}
      {push.status === 'needsHomeScreen' && <p>{t.pushNeedsHomeScreen}</p>}
      {push.status === 'denied' && <p>{t.pushDenied}</p>}
      {push.status === 'off' && (
        <>
          <p>{t.pushIntro}</p>
          <p>{t.pushDesktopHint}</p>
          <Button variant="primary" busy={push.busy} onClick={push.enable}>
            {t.pushEnable}
          </Button>
        </>
      )}
      {push.status === 'on' && (
        <>
          <p>{t.pushOn}</p>
          <p>{t.pushDesktopHint}</p>
          <Button busy={push.busy} onClick={push.disable}>
            {t.pushDisable}
          </Button>
        </>
      )}
      {push.error && <ErrorMessage>{t[push.error]}</ErrorMessage>}
    </section>
  )
}
