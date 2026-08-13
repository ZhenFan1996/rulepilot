import { expect, test } from '@playwright/test'

test('replaces route-owned background reads without cancelling durable work or publishing the old bundle', async ({ page }) => {
  let sessionReads = 0
  let activeReads = 0
  let oldActiveSettled = false
  let releaseOldActive!: () => void
  const oldActiveGate = new Promise<void>(resolve => { releaseOldActive = resolve })
  const cancelled: string[] = []
  const mutations: string[] = []

  page.on('requestfailed', request => {
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/assistant-runs/active' && url.searchParams.get('mode') === 'TEACHING') {
      cancelled.push(url.href)
    }
  })

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() !== 'GET') mutations.push(`${request.method()} ${url.pathname}`)
    if (url.pathname === '/api/auth/session') {
      sessionReads += 1
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/v1/assistant-runs/active' && url.searchParams.get('mode') === 'TEACHING') {
      activeReads += 1
      if (activeReads === 1) {
        await oldActiveGate
        await route.fulfill({ json: [{
          id: 'run-old', mode: 'TEACHING', subjectId: 'plan-old', ownerUsername: 'player',
          state: 'LESSON_COMPOSITION', updatedAt: '2026-08-13T00:00:00Z',
        }] }).catch(() => undefined)
        oldActiveSettled = true
        return
      }
      return route.fulfill({ json: [{
        id: 'run-current', mode: 'TEACHING', subjectId: 'plan-current', ownerUsername: 'player',
        state: 'LESSON_COMPOSITION', updatedAt: '2026-08-13T00:01:00Z',
      }] })
    }
    if (url.pathname === '/api/v1/teaching-plans') return route.fulfill({ json: [
      {
        id: 'plan-current', documentVersionId: 'version-current', gameTitle: '跨页面继续的讲解',
        premise: '路由切换不会丢失后台状态。', createdAt: '2026-08-13T00:00:00Z', sections: [],
      },
      {
        id: 'plan-old', documentVersionId: 'version-old', gameTitle: '过期讲解',
        premise: '不得覆盖。', createdAt: '2026-08-13T00:00:00Z', sections: [],
      },
    ] })
    if (url.pathname === '/api/v1/games') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports'
      || url.pathname === '/api/v1/documents/upload-teaching-handoffs'
      || url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/bgg/recommendations') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/')
  await expect.poll(() => activeReads).toBe(1)

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/catalog')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/catalog')
  await expect(page.getByRole('heading', { level: 1, name: '今晚想开哪一局？' })).toBeVisible()
  await expect.poll(() => activeReads).toBe(2)
  await expect.poll(() => cancelled.length).toBe(1)
  await page.getByTestId('background-work-trigger-desktop').click()
  const backgroundDialog = page.getByRole('dialog', { name: '后台任务' })
  await expect(backgroundDialog.getByText('跨页面继续的讲解', { exact: true })).toBeVisible()
  await expect(backgroundDialog.getByText('过期讲解', { exact: true })).toHaveCount(0)

  releaseOldActive()
  await expect.poll(() => oldActiveSettled).toBe(true)
  await expect(backgroundDialog.getByText('跨页面继续的讲解', { exact: true })).toBeVisible()
  await expect(backgroundDialog.getByText('过期讲解', { exact: true })).toHaveCount(0)
  expect(sessionReads).toBe(2)
  expect(mutations).toEqual([])
  expect(await page.evaluate(() => sessionStorage.getItem('rulepilot:active-teaching-runs:player')))
    .toContain('run-current')
})
