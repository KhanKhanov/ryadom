// Сквозной звонок: незрячий просит помощи, волонтёр принимает вызов, видит камеру незрячего и завершает звонок.
// Тексты — русские (locale ru-RU в playwright.config.ts), как их видит пользователь.

import { expect, test, type Browser, type Page } from '@playwright/test'

/** Логины уникальны для каждого прогона: в базе разработчика уже могут быть волонтёры и запросы. */
const run = Date.now().toString(36)

/**
 * Видимая часть страницы. Тексты состояний повторяются в скрытой области для экранного диктора (aria-live),
 * поэтому ищем их только на экране.
 */
function screen(page: Page) {
  return page.getByRole('main')
}

async function openAs(browser: Browser, login: string): Promise<Page> {
  // Отдельный контекст — как отдельный браузер: вход хранится в localStorage, общем для вкладок.
  const page = await (await browser.newContext()).newPage()
  await page.goto('/')
  await page.getByLabel('Логин').fill(login)
  await page.getByRole('button', { name: 'Войти', exact: true }).click()
  return page
}

test('незрячий получает помощь волонтёра по видео', async ({ browser }) => {
  // Волонтёр: роль, вызовы круглосуточно (иначе во время тишины вызов не придёт), связь с сервером.
  const volunteer = await openAs(browser, `e2e-volunteer-${run}`)
  await volunteer.getByRole('button', { name: 'Стать волонтёром' }).click()
  await volunteer.getByRole('button', { name: 'Изменить время тишины' }).click()
  await volunteer.getByLabel('Принимать вызовы круглосуточно').check()
  await volunteer.getByRole('button', { name: 'Сохранить' }).click()
  await expect(screen(volunteer).getByText('Вызовы приходят в любое время суток.')).toBeVisible()
  await expect(screen(volunteer).getByText('На связи с сервером.')).toBeVisible()

  // Незрячий: «тестовый незрячий» сайта публикует поддельную камеру Chrome.
  const blind = await openAs(browser, `e2e-blind-${run}`)
  await blind.getByRole('button', { name: 'Стать тестовым незрячим' }).click()
  await blind.getByRole('button', { name: 'Попросить помощи' }).click()
  await expect(screen(blind).getByText('Ищем волонтёра…')).toBeVisible()

  // Вызов приходит волонтёру — первой волной, если других волонтёров на связи нет, иначе одной из следующих.
  await volunteer.getByRole('button', { name: 'Принять' }).click({ timeout: 70_000 })

  // Звонок: оба в комнате LiveKit, волонтёр видит камеру незрячего.
  await expect(screen(volunteer).getByText('Звонок идёт.')).toBeVisible()
  await expect(screen(blind).getByText('Звонок идёт.')).toBeVisible()
  const video = volunteer.getByLabel('Видео с камеры собеседника')
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.videoWidth)).toBeGreaterThan(0)

  // Волонтёр завершает звонок; незрячий узнаёт об этом от сервера (событие request.ended).
  await volunteer.getByRole('button', { name: 'Завершить звонок' }).click()
  await expect(screen(volunteer).getByText('Удалось помочь?')).toBeVisible()
  await expect(screen(blind).getByText('Звонок завершён')).toBeVisible()
  await volunteer.getByRole('button', { name: 'Да' }).click()
  await expect(volunteer.getByRole('heading', { name: 'Кабинет волонтёра' })).toBeVisible()
})
