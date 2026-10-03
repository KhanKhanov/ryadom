// Настоящие реализации сервисов для браузера. В тестах вместо них — поддельные (src/testing).

import { ApiClient } from './api/client'
import { RealtimeConnection } from './api/realtime'
import { createLock, MemoryStorage, SessionStore } from './api/session'
import { createDeferredLiveKitCall } from './call/deferredCall'
import type { AppConfig } from './config'
import type { AppServices } from './ui/context'
import { createRinger } from './ui/ringer'

export function createBrowserServices(config: AppConfig): AppServices {
  const api = new ApiClient({
    baseUrl: config.apiBaseUrl,
    store: new SessionStore(storageOrMemory(() => window.localStorage)),
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
    tabStorage: storageOrMemory(() => window.sessionStorage),
    navigate: (url) => window.location.assign(url),
    now: () => new Date(),
  }
}

/**
 * Браузер может запретить сайту хранилище (настройки приватности) — тогда обращение к нему бросает исключение.
 * В этом случае данные хранятся в памяти: вход живёт до перезагрузки страницы, а вход через Яндекс
 * не завершится (данные начатого входа не переживут переход на сайт Яндекса), но сайт открывается.
 */
function storageOrMemory(get: () => Storage): Storage {
  try {
    return get()
  } catch {
    return new MemoryStorage()
  }
}
