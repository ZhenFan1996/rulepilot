import { expect, test } from '@playwright/test'

test('shows readable My Guides from the batched list before run hydration settles', async ({ page }) => {
  let sessionReads = 0
  let releaseRun!: () => void
  const runGate = new Promise<void>(resolve => { releaseRun = resolve })
  let progressReadsStarted = 0
  let progressReadsSettled = 0
  const cancelled: string[] = []
  const mutations: string[] = []

  page.on('requestfailed', request => {
    const url = new URL(request.url())
    if (isProgressRead(url)) cancelled.push(url.href)
  })

  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() !== 'GET') mutations.push(`${request.method()} ${url.pathname}`)
    if (url.pathname === '/api/auth/session') {
      sessionReads += 1
      return route.fulfill({ json: { username: 'player', roles: ['USER'] } })
    }
    if (url.pathname === '/api/v1/teaching-plans') {
      return route.fulfill({ json: [{
        id: 'plan-progress', documentVersionId: 'version-progress', gameTitle: '快速可见的讲解',
        premise: '列表不再等待慢进度。', createdAt: '2026-08-13T00:00:00Z',
        sections: [{
          position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: false,
        }],
        lesson: {
          id: 'lesson-progress', teachingPlanId: 'plan-progress', status: 'DRAFT_READY',
          sections: [{ evidenceStatus: 'CITED_DRAFT' }],
        },
      }] })
    }
    if (url.pathname === '/api/v1/assistant-runs/latest' && url.searchParams.get('subjectId') === 'plan-progress') {
      progressReadsStarted += 1
      await runGate
      await route.fulfill({ json: {
        run: {
          id: 'run-progress', subjectId: 'plan-progress', state: 'RETRIEVING',
          createdAt: '2026-08-13T00:00:00Z', updatedAt: '2026-08-13T00:01:00Z', completedAt: null,
          lastErrorCode: null,
        },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      } }).catch(() => undefined)
      progressReadsSettled += 1
      return
    }
    if (url.pathname.endsWith('/illustrated-lessons/latest/summary')) {
      throw new Error('the batched list must avoid a second lesson progress request')
    }
    if (isEmptyBackgroundPath(url)) return route.fulfill({ json: [] })
    return route.fulfill({ status: 404 })
  })

  await page.goto('/lessons')
  await expect.poll(() => progressReadsStarted).toBe(1)
  await expect(page.getByRole('heading', { level: 2, name: '快速可见的讲解' })).toBeVisible()
  await expect(page.getByRole('link', { name: '阅读完整讲解' })).toBeVisible()
  await expect(page.getByText('正在读取讲解…')).toHaveCount(0)
  expect(sessionReads).toBe(1)

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/catalog')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/catalog')
  await expect(page.getByRole('heading', { level: 1, name: '今晚想开哪一局？' })).toBeVisible()
  await expect.poll(() => cancelled.length).toBe(1)
  expect(mutations).toEqual([])
  expect(cancelled.every(url => /assistant-runs\/latest|illustrated-lessons\/latest/.test(url))).toBe(true)

  releaseRun()
  await expect.poll(() => progressReadsSettled).toBe(1)
  await expect(page.getByRole('heading', { level: 1, name: '今晚想开哪一局？' })).toBeVisible()
  expect(sessionReads).toBe(2)
})

function isProgressRead(url: URL) {
  return url.pathname === '/api/v1/assistant-runs/latest' && url.searchParams.get('subjectId') === 'plan-progress'
}

function isEmptyBackgroundPath(url: URL) {
  return url.pathname === '/api/v1/documents/official-imports'
    || url.pathname === '/api/v1/documents/upload-teaching-handoffs'
    || url.pathname === '/api/v1/documents'
    || url.pathname === '/api/v1/games'
    || url.pathname === '/api/v1/assistant-runs/active'
}
