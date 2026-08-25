import { expect, test, type Route } from '@playwright/test'

type LessonRequestKind = 'plan' | 'lesson' | 'teaching-run' | 'catalog-presentation' | 'visual-run' | 'comprehension'
type LessonFailureDomain = 'core' | 'optional-visual' | 'supporting'
type ClassifiedLessonRequest = { kind: LessonRequestKind; domain: LessonFailureDomain; url: string }

test('cancels the replaced private-guide bundle and renders only the current plan', async ({ page }) => {
  let releaseFirstBundle!: () => void
  const firstBundleGate = new Promise<void>(resolve => { releaseFirstBundle = resolve })
  const firstBundleStarted: ClassifiedLessonRequest[] = []
  const firstBundleSettled: ClassifiedLessonRequest[] = []
  const cancelled: ClassifiedLessonRequest[] = []
  const currentPlanStarted: ClassifiedLessonRequest[] = []

  page.on('requestfailed', request => {
    const classified = classifyLessonRequest(new URL(request.url()), 'plan-1')
    if (classified) cancelled.push(classified)
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

    const firstPlanRequest = classifyLessonRequest(url, 'plan-1')
    if (firstPlanRequest) {
      firstBundleStarted.push(firstPlanRequest)
      await firstBundleGate
      await fulfillLessonRead(route, url, 'plan-1', '已经过期的第一份讲解').catch(() => undefined)
      firstBundleSettled.push(firstPlanRequest)
      return
    }
    const currentPlanRequest = classifyLessonRequest(url, 'plan-2')
    if (currentPlanRequest) {
      currentPlanStarted.push(currentPlanRequest)
      return fulfillLessonRead(route, url, 'plan-2', '当前计划的讲解')
    }
    return route.fulfill({ status: 404 })
  })

  await page.goto('/lesson/plan-1')
  await expect.poll(() => requestKinds(firstBundleStarted, 'core')).toEqual([
    'catalog-presentation',
    'lesson',
    'plan',
    'teaching-run',
  ])
  expect(requestKinds(firstBundleStarted, 'optional-visual')).toEqual([])
  expect(requestKinds(firstBundleStarted, 'supporting')).toEqual([])

  await page.evaluate(() => {
    window.history.pushState(window.history.state, '', '/lesson/plan-2')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })

  await expect(page).toHaveURL('/lesson/plan-2')
  await expect(page.getByRole('heading', { level: 1, name: '当前计划的讲解' })).toBeVisible()
  await expect.poll(() => requestLabels(cancelled)).toEqual(requestLabels(firstBundleStarted))
  await expect.poll(() => requestKinds(currentPlanStarted, 'optional-visual'))
    .toContain('visual-run')

  releaseFirstBundle()
  await expect.poll(() => requestLabels(firstBundleSettled)).toEqual(requestLabels(firstBundleStarted))
  await expect(page.getByRole('heading', { level: 1, name: '当前计划的讲解' })).toBeVisible()
  await expect(page.getByText('已经过期的第一份讲解', { exact: true })).toHaveCount(0)
  expect(requestKinds(firstBundleStarted, 'optional-visual')).toEqual([])
})

function classifyLessonRequest(url: URL, planId: string): ClassifiedLessonRequest | null {
  const classified = (kind: LessonRequestKind, domain: LessonFailureDomain) => ({
    kind,
    domain,
    url: url.toString(),
  })
  if (url.pathname === `/api/v1/teaching-plans/${planId}`) return classified('plan', 'core')
  if (url.pathname === `/api/v1/teaching-plans/${planId}/illustrated-lessons/latest`) {
    return classified('lesson', 'core')
  }
  if (url.pathname === `/api/v1/teaching-plans/${planId}/catalog-presentation`) {
    return classified('catalog-presentation', 'core')
  }
  if (url.pathname === `/api/v1/teaching-plans/${planId}/comprehension`) {
    return classified('comprehension', 'supporting')
  }
  if (url.pathname !== '/api/v1/assistant-runs/latest'
    || url.searchParams.get('subjectId') !== planId) return null
  if (url.searchParams.get('mode') === 'TEACHING') return classified('teaching-run', 'core')
  if (url.searchParams.get('mode') === 'VISUAL_ENRICHMENT') {
    return classified('visual-run', 'optional-visual')
  }
  return null
}

function requestKinds(requests: ClassifiedLessonRequest[], domain: LessonFailureDomain) {
  return requests
    .filter(request => request.domain === domain)
    .map(request => request.kind)
    .sort()
}

function requestLabels(requests: ClassifiedLessonRequest[]) {
  return requests.map(request => `${request.domain}:${request.kind}:${request.url}`).sort()
}

function fulfillLessonRead(route: Route, url: URL, planId: string, title: string) {
  if (url.pathname === `/api/v1/teaching-plans/${planId}`) {
    return route.fulfill({ json: {
      id: planId,
      documentVersionId: `version-${planId}`,
      gameTitle: title,
      premise: '生命周期回归',
      sections: [{ position: 1, title, visualEvidenceRecommended: true }],
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
  if (url.pathname === '/api/v1/assistant-runs/latest') {
    const mode = url.searchParams.get('mode')
    return route.fulfill({ json: {
      run: {
        id: `${mode === 'VISUAL_ENRICHMENT' ? 'visual' : 'teaching'}-${planId}`,
        subjectId: planId,
        state: 'COMPLETED',
        createdAt: '2026-08-26T00:00:00Z',
        updatedAt: '2026-08-26T00:01:00Z',
        completedAt: '2026-08-26T00:01:00Z',
        lastErrorCode: null,
      },
      budget: { usedModelCalls: 1, maxModelCalls: 10 },
      activities: [],
    } })
  }
  if (url.pathname.endsWith('/catalog-presentation')) return route.fulfill({ status: 404 })
  if (url.pathname.endsWith('/comprehension')) return route.fulfill({ status: 404 })
  return route.fulfill({ status: 404 })
}
