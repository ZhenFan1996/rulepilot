import { expect, test, type Route } from '@playwright/test'

test('cancels a replaced public lesson read and reuses the shell session identity for Q&A', async ({ page }) => {
  let sessionReads = 0
  let releaseFirstLesson!: () => void
  const firstLessonGate = new Promise<void>(resolve => { releaseFirstLesson = resolve })
  let firstLessonStarted = 0
  let firstLessonSettled = 0
  const cancelled: string[] = []

  page.on('requestfailed', request => {
    if (new URL(request.url()).pathname === '/api/public/lessons/plan-1') cancelled.push(request.url())
  })

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') {
      sessionReads += 1
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })

    if (url.pathname === '/api/public/lessons/plan-1') {
      firstLessonStarted += 1
      await firstLessonGate
      await fulfillPublicLesson(route, 'plan-1', '已经过期的公开讲解').catch(() => undefined)
      firstLessonSettled += 1
      return
    }
    if (url.pathname === '/api/public/lessons/plan-2') {
      return fulfillPublicLesson(route, 'plan-2', '当前公开讲解')
    }
    return route.fulfill({ status: 404 })
  })

  await page.goto('/read/plan-1/questions')
  await expect.poll(() => firstLessonStarted).toBe(1)

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/read/plan-2/questions')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/read/plan-2/questions')
  await expect(page.getByRole('heading', { level: 1, name: '向《当前公开讲解》规则书提问' })).toBeVisible()
  await expect(page.locator('#public-question')).toBeEnabled()
  await expect.poll(() => cancelled.length).toBe(1)
  expect(sessionReads).toBe(1)

  releaseFirstLesson()
  await expect.poll(() => firstLessonSettled).toBe(1)
  await expect(page.getByRole('heading', { level: 1, name: '向《当前公开讲解》规则书提问' })).toBeVisible()
  await expect(page.getByText('已经过期的公开讲解', { exact: true })).toHaveCount(0)
})

function fulfillPublicLesson(route: Route, planId: string, title: string) {
  return route.fulfill({ json: {
    teachingPlanId: planId,
    documentVersionId: `version-${planId}`,
    rulebookTitle: title,
    officialSourceUrl: null,
    gameCover: null,
    publicGame: null,
    contentLanguage: 'zh-CN',
    localizationStatus: 'READY',
    lesson: {
      id: `lesson-${planId}`,
      status: 'COMPLETE',
      sections: [],
    },
  } })
}
