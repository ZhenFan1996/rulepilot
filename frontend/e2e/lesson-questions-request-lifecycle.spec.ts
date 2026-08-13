import { expect, test, type Route } from '@playwright/test'

test('cancels the replaced Q&A workspace bundle and reuses the shell session identity', async ({ page }) => {
  let sessionReads = 0
  let releaseFirstBundle!: () => void
  const firstBundleGate = new Promise<void>(resolve => { releaseFirstBundle = resolve })
  let firstBundleStarted = 0
  let firstBundleSettled = 0
  const cancelled: string[] = []

  page.on('requestfailed', request => {
    if (request.url().includes('/teaching-plans/plan-1')) cancelled.push(request.url())
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

    if (url.pathname.includes('/teaching-plans/plan-1')) {
      firstBundleStarted += 1
      await firstBundleGate
      await fulfillWorkspaceRead(route, url, 'plan-1', '已经过期的第一份答疑').catch(() => undefined)
      firstBundleSettled += 1
      return
    }
    if (url.pathname.includes('/teaching-plans/plan-2')) {
      return fulfillWorkspaceRead(route, url, 'plan-2', '当前计划的答疑')
    }
    return route.fulfill({ status: 404 })
  })

  await page.goto('/lesson/plan-1/questions')
  await expect.poll(() => firstBundleStarted).toBe(3)

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/lesson/plan-2/questions')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/lesson/plan-2/questions')
  await expect(page.getByRole('heading', { level: 1, name: '向《当前计划的答疑》规则书提问' })).toBeVisible()
  await expect.poll(() => cancelled.length).toBe(3)
  expect(sessionReads).toBe(1)

  releaseFirstBundle()
  await expect.poll(() => firstBundleSettled).toBe(3)
  await expect(page.getByRole('heading', { level: 1, name: '向《当前计划的答疑》规则书提问' })).toBeVisible()
  await expect(page.getByText('已经过期的第一份答疑', { exact: true })).toHaveCount(0)
})

function fulfillWorkspaceRead(route: Route, url: URL, planId: string, title: string) {
  if (url.pathname === `/api/v1/teaching-plans/${planId}`) {
    return route.fulfill({ json: {
      id: planId,
      documentVersionId: `version-${planId}`,
      gameTitle: title,
    } })
  }
  if (url.pathname.endsWith('/illustrated-lessons/latest')) {
    return route.fulfill({ json: {
      id: `lesson-${planId}`,
      teachingPlanId: planId,
    } })
  }
  if (url.pathname.endsWith('/catalog-presentation')) {
    return route.fulfill({ json: {
      editionId: `edition-${planId}`,
      gameName: title,
      editionName: null,
      language: 'zh-CN',
      publicationYear: 2026,
      bggId: 42,
      thumbnailUrl: null,
      minPlayers: 1,
      maxPlayers: 4,
      playingTimeMinutes: 60,
      minimumAge: 10,
      bggUrl: 'https://boardgamegeek.com/boardgame/42',
    } })
  }
  return route.fulfill({ status: 404 })
}
