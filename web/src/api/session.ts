// Хранение токенов входа и блокировка их обновления между вкладками.

/** Токены вошедшего пользователя. */
export type StoredSession = {
  /** id пользователя — чтобы заметить, что в другой вкладке вошли под другим именем. */
  userId: string
  accessToken: string
  /** Когда истечёт access-токен, миллисекунды Unix-времени по часам этого компьютера. */
  accessTokenExpiresAt: number
  refreshToken: string
}

const STORAGE_KEY = 'ryadom.session'

/**
 * Токены в localStorage: вход сохраняется после перезагрузки страницы и общий у всех вкладок сайта.
 * Это важно и для push-уведомлений (этап 5): волонтёр открывает сайт из уведомления уже вошедшим.
 * Доступ к localStorage есть у любого скрипта страницы, поэтому на сайте не должно быть чужих скриптов.
 */
export class SessionStore {
  private readonly storage: Storage | null

  constructor(storage: Storage | null) {
    this.storage = storage
  }

  get(): StoredSession | null {
    try {
      const raw = this.storage?.getItem(STORAGE_KEY)
      if (!raw) return null
      const value: unknown = JSON.parse(raw)
      return isStoredSession(value) ? value : null
    } catch {
      // Хранилище недоступно (запрещено настройками браузера) или в нём мусор — считаем, что входа нет.
      return null
    }
  }

  set(session: StoredSession): void {
    try {
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(session))
    } catch {
      // Без хранилища вход проживёт только до перезагрузки страницы — это лучше, чем ошибка.
    }
  }

  clear(): void {
    try {
      this.storage?.removeItem(STORAGE_KEY)
    } catch {
      // См. set().
    }
  }

  /**
   * Сообщает, что токены поменяла другая вкладка (вход, обновление, выход).
   * Изменения в этой же вкладке браузер о себе не сообщает.
   */
  subscribe(target: Pick<Window, 'addEventListener' | 'removeEventListener'>, listener: () => void): () => void {
    const onStorage = (event: StorageEvent) => {
      // key === null — хранилище очищено целиком.
      if (event.key === STORAGE_KEY || event.key === null) listener()
    }
    target.addEventListener('storage', onStorage)
    return () => target.removeEventListener('storage', onStorage)
  }
}

function isStoredSession(value: unknown): value is StoredSession {
  if (typeof value !== 'object' || value === null) return false
  const session = value as Record<string, unknown>
  return (
    typeof session.userId === 'string' &&
    typeof session.accessToken === 'string' &&
    typeof session.accessTokenExpiresAt === 'number' &&
    typeof session.refreshToken === 'string'
  )
}

/** Выполняет задачу, пока никто другой не держит блокировку с тем же именем. */
export type LockRunner = <T>(name: string, task: () => Promise<T>) => Promise<T>

/**
 * Блокировка между вкладками (Web Locks API). Нужна для обновления токенов: refresh-токен одноразовый,
 * и если две вкладки обновят его одновременно, сервер примет второе обновление за кражу токена
 * и завершит все сеансы пользователя (docs/api/openapi.yaml, «Авторизация»).
 * Где Web Locks нет (старые браузеры, тесты), задачи выстраиваются в очередь внутри вкладки.
 */
export function createLock(locks: LockManager | null = globalThis.navigator?.locks ?? null): LockRunner {
  if (locks) return (name, task) => locks.request(name, task)
  const queues = new Map<string, Promise<unknown>>()
  return <T>(name: string, task: () => Promise<T>) => {
    const previous = queues.get(name) ?? Promise.resolve()
    const run = previous.then(task, task)
    queues.set(
      name,
      run.catch(() => undefined),
    )
    return run
  }
}
