import { expect, test, type Route } from '@playwright/test'

test('cancels the replaced private-guide bundle and renders only the current plan', async ({ page }) => {
  let releaseFirstBundle!: () => void
  const firstBundleGate = new Promise<void>(resolve => { releaseFirstBundle = resolve })
  let firstBundleStarted = 0
  let firstBundleSettled = 0
  const cancelled: string[] = []

  page.on('requestfailed', request => {
    const url = request.url()
    if (url.includes('plan-1')) cancelled.push(url)
  })

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/session') {
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/v1/assistant-runs/active') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/official-imports') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents/upload-teaching-handoffs') return route.fulfill({ json: [] })
    if (url.pathname === '/api/v1/documents') return route.fulfill({ json: [] })

    if (requestBelongsTo(url, 'plan-1')) {
      firstBundleStarted += 1
      await firstBundleGate
      await fulfillLessonRead(route, url, 'plan-1', '已经过期的第一份讲解').catch(() => undefined)
      firstBundleSettled += 1
      return
    }
    if (requestBelongsTo(url, 'plan-2')) {
      return fulfillLessonRead(route, url, 'plan-2', '当前计划的讲解')
    }
    return route.fulfill({ status: 404 })
  })

  await page.goto('/lesson/plan-1')
  await expect.poll(() => firstBundleStarted).toBe(5)

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/lesson/plan-2')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/lesson/plan-2')
  await expect(page.getByRole('heading', { level: 1, name: '当前计划的讲解' })).toBeVisible()
  await expect.poll(() => cancelled.length).toBe(5)

  releaseFirstBundle()
  await expect.poll(() => firstBundleSettled).toBe(5)
  await expect(page.getByRole('heading', { level: 1, name: '当前计划的讲解' })).toBeVisible()
  await expect(page.getByText('已经过期的第一份讲解', { exact: true })).toHaveCount(0)
})

function requestBelongsTo(url: URL, planId: string) {
  return url.pathname.includes(`/teaching-plans/${planId}`)
    || url.pathname === '/api/v1/assistant-runs/latest' && url.searchParams.get('subjectId') === planId
}

function fulfillLessonRead(route: Route, url: URL, planId: string, title: string) {
  if (url.pathname === `/api/v1/teaching-plans/${planId}`) {
    return route.fulfill({ json: {
      id: planId,
      documentVersionId: `version-${planId}`,
      gameTitle: title,
      premise: '生命周期回归',
      sections: [{ position: 1, title, visualEvidenceRecommended: false }],
    } })
  }
  if (url.pathname.endsWith('/illustrated-lessons/latest')) {
    return route.fulfill({ json: {
      id: `lesson-${planId}`,
      teachingPlanId: planId,
      status: 'COMPLETE',
      sections: [{
        position: 1,
        topicKey: 'setup',
        coverageTags: ['setup'],
        title,
        required: true,
        evidenceStatus: 'SUPPORTED',
        visualKind: 'TABLE_LAYOUT',
        visualCaption: '',
        visualSourcePages: [1],
        visualSourceChunkIds: ['chunk-1'],
        steps: [{
          position: 1,
          heading: '读取当前计划',
          kind: 'DO',
          text: title,
          sourcePages: [1],
          visualFocus: null,
        }],
      }],
    } })
  }
  if (url.pathname === '/api/v1/assistant-runs/latest') return route.fulfill({ status: 404 })
  if (url.pathname.endsWith('/catalog-presentation')) return route.fulfill({ status: 404 })
  if (url.pathname.endsWith('/comprehension')) return route.fulfill({ status: 404 })
  return route.fulfill({ status: 404 })
}
