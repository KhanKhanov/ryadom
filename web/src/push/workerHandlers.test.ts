import { NOTIFICATION_CLICK_MESSAGE, parsePushMessage } from './pushMessage'
import { fromBase64Url } from './webPush'
import { handleNotificationClick, handlePush, type ShownNotification, type SiteWindow, type WorkerContext } from './workerHandlers'

/** Service Worker без браузера: показанные уведомления и вкладки — в памяти. */
function fakeWorker(options: { windows?: SiteWindow[]; now?: number; language?: 'ru' | 'en' } = {}) {
  const shown: (ShownNotification & { title: string; options: NotificationOptions; closed: boolean })[] = []
  const opened: string[] = []
  const context: WorkerContext = {
    language: options.language ?? 'ru',
    now: () => options.now ?? 1_000_000,
    async showNotification(title, notificationOptions) {
      const notification = {
        title,
        options: notificationOptions,
        tag: notificationOptions.tag ?? '',
        data: notificationOptions.data,
        closed: false,
        close: () => (notification.closed = true),
      }
      shown.push(notification)
    },
    notifications: async () => shown.filter((n) => !n.closed),
    windows: async () => options.windows ?? [],
    async openWindow(url) {
      opened.push(url)
    },
  }
  return { context, shown, opened }
}

function siteWindow(visible: boolean) {
  const messages: unknown[] = []
  let focused = false
  const window: SiteWindow = {
    visible,
    focus: async () => (focused = true),
    postMessage: (message) => messages.push(message),
  }
  return { window, messages, isFocused: () => focused }
}

describe('service worker', () => {
  it('shows a notification about a new call', async () => {
    const { context, shown } = fakeWorker()

    await handlePush(context, { type: 'request.incoming', requestId: 'request-1' })

    expect(shown).toHaveLength(1)
    expect(shown[0].title).toBe('Нужна помощь')
    expect(shown[0].options).toMatchObject({
      body: 'Незрячему человеку нужна помощь по видео. Нажмите, чтобы ответить.',
      tag: 'request-1',
      lang: 'ru',
      requireInteraction: true,
      silent: false,
    })
  })

  it('speaks the language of the browser', async () => {
    const { context, shown } = fakeWorker({ language: 'en' })

    await handlePush(context, { type: 'request.incoming', requestId: 'request-1' })

    expect(shown[0].title).toBe('Someone needs help')
  })

  it('still shows the notification, silently, when the site is on screen: Safari drops subscriptions after silent pushes', async () => {
    const { context, shown } = fakeWorker({ windows: [siteWindow(true).window] })

    await handlePush(context, { type: 'request.incoming', requestId: 'request-1' })

    expect(shown).toHaveLength(1)
    expect(shown[0].options.silent).toBe(true)
  })

  it('removes notifications about calls that are long over', async () => {
    const { context, shown } = fakeWorker({ now: 1_000_000 })
    await handlePush({ ...context, now: () => 1_000_000 - 3 * 60_000 }, { type: 'request.incoming', requestId: 'old' })
    await handlePush({ ...context, now: () => 1_000_000 - 30_000 }, { type: 'request.incoming', requestId: 'recent' })

    await handlePush(context, { type: 'request.incoming', requestId: 'new' })

    expect(shown.filter((n) => !n.closed).map((n) => n.tag)).toEqual(['recent', 'new'])
  })

  it('closes the notification of a closed call', async () => {
    const { context, shown } = fakeWorker()
    await handlePush(context, { type: 'request.incoming', requestId: 'request-1' })

    await handlePush(context, { type: 'request.closed', requestId: 'request-1' })

    expect(shown.map((n) => n.closed)).toEqual([true])
  })

  it('ignores pushes it does not understand', async () => {
    const { context, shown } = fakeWorker()

    await handlePush(context, { type: 'request.reminder', requestId: 'request-1' })
    await handlePush(context, null)
    await handlePush(context, { type: 'request.incoming' })

    expect(shown).toHaveLength(0)
  })

  it('brings the open site to front and asks it to recheck calls', async () => {
    const hidden = siteWindow(false)
    const visible = siteWindow(true)
    const { context, opened } = fakeWorker({ windows: [hidden.window, visible.window] })
    let closed = false

    await handleNotificationClick(context, { tag: 'request-1', data: null, close: () => (closed = true) })

    expect(closed).toBe(true)
    expect(visible.isFocused()).toBe(true)
    expect(visible.messages).toEqual([{ type: NOTIFICATION_CLICK_MESSAGE }])
    expect(hidden.isFocused()).toBe(false)
    expect(opened).toEqual([])
  })

  it('opens the site when it is not open', async () => {
    const { context, opened } = fakeWorker()

    await handleNotificationClick(context, { tag: 'request-1', data: null, close: () => undefined })

    expect(opened).toEqual(['/'])
  })

  it('reads only known push messages', () => {
    expect(parsePushMessage({ type: 'request.closed', requestId: 'r', extra: 1 })).toEqual({ type: 'request.closed', requestId: 'r' })
    expect(parsePushMessage({ type: 'request.incoming', requestId: '' })).toBeNull()
    expect(parsePushMessage('request.incoming')).toBeNull()
  })

  it('decodes the server key for the browser', () => {
    expect([...fromBase64Url('AQID_-8')]).toEqual([1, 2, 3, 255, 239])
  })
})
