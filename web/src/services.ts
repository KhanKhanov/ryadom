// Настоящие реализации сервисов для браузера. В тестах вместо них — поддельные (src/testing).

import { ApiClient } from './api/client'
import { RealtimeConnection } from './api/realtime'
import { createLock, SessionStore } from './api/session'
import { createDeferredLiveKitCall } from './call/deferredCall'
import type { AppConfig } from './config'
import type { AppServices } from './ui/context'
import { createRinger } from './ui/ringer'

export function createBrowserServices(config: AppConfig): AppServices {
  const api = new ApiClient({
    baseUrl: config.apiBaseUrl,
    store: new SessionStore(storageOrNull(() => window.localStorage)),
    lock: createLock(),
  })
  return {
    config,
    api,
    connectRealtime(handlers) {
      const connection = new RealtimeConnection({ url: config.realtimeUrl, auth: api, handlers })
      connection.start()
      return connection
    },
    createCall: createDeferredLiveKitCall,
    ringer: createRinger(),
    tabStorage: window.sessionStorage,
    navigate: (url) => window.location.assign(url),
    now: () => new Date(),
  }
}

/** Браузер может запретить хранилище (настройки приватности) — тогда обращение к нему бросает исключение. */
function storageOrNull(get: () => Storage): Storage | null {
  try {
    return get()
  } catch {
    return null
  }
}
