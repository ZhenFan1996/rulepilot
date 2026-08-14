import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonView from './LessonView.vue'
import { setLocale } from '@/lib/locale'

describe('LessonView progressive reading', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-21T00:02:00Z'))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
    setLocale('zh-CN')
  })

  it('keeps the reader in place, opens the next published chapter, and unlocks final actions at terminal state', async () => {
    let runReads = 0
    let lessonReads = 0
    let qualityReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'SETI', premise: '寻找生命',
          sections: [
            { position: 1, title: '先摆主板', visualEvidenceRecommended: true },
            { position: 2, title: '开始第一轮', visualEvidenceRecommended: false },
          ],
        })
      }
      if (path === '/api/v1/teaching-plans/plan-1/catalog-presentation') {
        return Response.json(catalogPresentationFixture('目录桌游'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('/api/v1/assistant-runs/latest')) {
        runReads++
        if (runReads === 1) throw new TypeError('temporary run status failure')
        return Response.json({
          run: {
            id: 'run-1', subjectId: 'plan-1',
            state: runReads >= 3 ? 'COMPLETED' : 'RETRIEVING',
            createdAt: '2026-07-21T00:00:00Z', updatedAt: '2026-07-21T00:01:00Z',
            completedAt: runReads >= 3 ? '2026-07-21T00:02:00Z' : null, lastErrorCode: null,
          },
          budget: { usedModelCalls: runReads >= 3 ? 2 : 1, maxModelCalls: 144 },
          activities: runReads >= 3
            ? [{
                sequence: 2, type: 'VALIDATION', operation: 'publishTeachingSection|1', summary: 'published',
                outcome: 'SUCCEEDED', latencyMs: 0, occurredAt: '2026-07-21T00:02:00Z',
              }]
            : [{
                sequence: 1, type: 'MODEL', operation: 'composeTeachingSection|1', summary: 'internal',
                outcome: 'RUNNING', latencyMs: 0, occurredAt: '2026-07-21T00:01:00Z',
              }],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads++
        const sections = [section(1, '先摆主板')]
        if (lessonReads >= 2) sections.push(section(2, '开始第一轮'))
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1',
          status: lessonReads >= 3 ? 'COMPLETE' : 'INCOMPLETE', sections,
        })
      }
      if (path.endsWith('/illustrated-lessons/latest/quality')) {
        qualityReads++
        return Response.json({ status: 'READY', score: 100, checks: [] })
      }
      if (path.endsWith('/comprehension')) {
        return Response.json({
          lessonId: 'lesson-1', readyTaskCount: 0, taskCount: 0, canDoCount: 0, needsHelpCount: 0,
          readyVisualTaskCount: 2, visualAidRatedCount: 1, visualAidHelpfulCount: 1, visualAidHelpfulPercent: 100, tasks: [],
          visualAids: [
            { key: 's1-v3', label: '主棋盘区域', chapterPosition: 1, sourcePages: [1], result: 'HELPFUL',
              visualFocus: { pageNumber: 1, label: '主棋盘区域', x: 100, y: 200, width: 500, height: 400 } },
            { key: 's2-v3', label: '行动区', chapterPosition: 2, sourcePages: [2], result: 'NOT_RATED',
              visualFocus: { pageNumber: 2, label: '行动区', x: 100, y: 200, width: 500, height: 400 } },
          ],
        })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/comprehension/visual-aids/s2-v3')) {
        return Response.json({
          lessonId: 'lesson-1', readyTaskCount: 0, taskCount: 0, canDoCount: 0, needsHelpCount: 0,
          readyVisualTaskCount: 2, visualAidRatedCount: 2, visualAidHelpfulCount: 2, visualAidHelpfulPercent: 100, tasks: [], visualAids: [],
        })
      }
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()

    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          CardOcrCapture: true,
          VoiceQuestionCapture: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('正在确认后台生成状态')
    expect(wrapper.text()).toContain('先摆主板')
    expect(wrapper.text()).toContain('我的图文讲解')
    expect(wrapper.text()).toContain('目录桌游')
    expect(wrapper.text()).toContain('SETI')
    expect(wrapper.text()).toContain('1–5 人')
    expect(wrapper.text()).toContain('桌游资料由 BoardGameGeek 提供')
    expect(wrapper.text()).toContain('讲解与答疑仍只依据已上传规则书及其引用')
    expect(wrapper.get('[data-testid="catalog-game-presentation"] a').attributes('href'))
      .toBe('https://boardgamegeek.com/boardgame/42')
    expect(wrapper.get('img[alt="目录桌游 的 BGG 封面"]').attributes('src'))
      .toBe('https://example.test/catalog-cover.jpg')
    expect(wrapper.text()).toContain('图中看什么')
    expect(wrapper.text()).toContain('主棋盘中央有三条相连的行动轨道。')
    expect(wrapper.text()).toContain('规则答疑')
    expect(wrapper.text()).not.toContain('答疑独立打开，不打断当前讲解')
    expect(wrapper.get('[data-testid="private-lesson-surface"]').classes()).not.toContain('overflow-x-hidden')
    expect(wrapper.text()).not.toContain('图标速查表')
    expect(wrapper.find('#lesson-question-panel').exists()).toBe(false)
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('规则答疑')
    expect(wrapper.get('[data-testid="lesson-questions-entry"]').classes()).not.toContain('fixed')
    expect(wrapper.find('a[href^="/lesson/plan-1/questions?section="]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('问这一章')
    expect(wrapper.text()).not.toContain('开始对局')
    expect(wrapper.text()).not.toContain('4 人 ·')
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img[alt*="主棋盘区域"]').attributes('src'))
      .toContain('/pages/1/image/crop?x=100&y=200&width=500&height=400')
    expect(wrapper.findAll('[data-testid="private-rule-step"]')).toHaveLength(5)
    expect(qualityReads).toBe(0)

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('开始第一轮')
    expect(wrapper.text()).toContain('整本仍在后台生成')
    expect(wrapper.text()).toContain('正在依据规则书编写“先摆主板”')
    expect(wrapper.text()).toContain('后台已处理 0/2 节')
    expect(wrapper.text()).toContain('1 次模型调用')
    expect(wrapper.text()).toContain('第一节完成后')
    expect(wrapper.text()).not.toContain('internal')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.text()).not.toContain('整本仍在后台生成')
    expect(qualityReads).toBe(0)
    expect(wrapper.text()).toContain('逐张看看这些规则书裁剪图')
    expect(wrapper.text()).toContain('焦点图有帮助 1 / 1（100%）')
    expect(wrapper.get('img[alt*="行动区"]').attributes('src'))
      .toContain('/pages/2/image/crop?x=100&y=200&width=500&height=400')
    const helpfulButtons = wrapper.findAll('button').filter((button) => button.text() === '有帮助')
    expect(helpfulButtons).toHaveLength(2)
    await helpfulButtons[1]!.trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toContain('/api/v1/teaching-plans/plan-1/comprehension/visual-aids/s2-v3')
    const progressPaths = fetchMock.mock.calls
      .map(([input]) => String(input))
      .filter((path) => path.includes('mode=TEACHING'))
    expect(progressPaths[2]).toContain('activityRunId=run-1&afterActivitySequence=1')
    const supportingPaths = fetchMock.mock.calls.map(([input]) => String(input))
    expect(supportingPaths.some((path) => path.includes('icon-glossary'))).toBe(false)
    expect(supportingPaths.some((path) => path.includes('/narration'))).toBe(false)
    expect(supportingPaths.some((path) => path.endsWith('/video'))).toBe(false)
    expect(supportingPaths.some((path) => path.includes('media-consistency'))).toBe(false)
    wrapper.unmount()
  })

  it('lets the player use a complete cited draft while factual review remains active', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'SETI', premise: '寻找生命',
          sections: [{ position: 1, title: '先摆主板', visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('/api/v1/assistant-runs/latest')) {
        return Response.json({
          run: {
            id: 'run-1', subjectId: 'plan-1', state: 'VERIFYING_EVIDENCE', createdAt: '2026-07-21T00:00:00Z',
            updatedAt: '2026-07-21T00:01:00Z', completedAt: null, lastErrorCode: null,
          },
          budget: { usedModelCalls: 2, maxModelCalls: 48 },
          activities: [{
            sequence: 2, type: 'CRITIC', operation: 'reviewPublishedTeachingSection', summary: 'Work started',
            outcome: 'RUNNING', latencyMs: 0, occurredAt: '2026-07-21T00:01:00Z',
          }],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [{ ...section(1, '先摆主板'), evidenceStatus: 'CITED_DRAFT' }],
        })
      }
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          CardOcrCapture: true,
          VoiceQuestionCapture: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('完整基础讲解已经可用')
    expect(wrapper.text()).toContain('后台只是在核对和修正细节')
    expect(wrapper.text()).toContain('我的图文讲解')
    expect(wrapper.text()).not.toContain('问这一章')
    expect(wrapper.text()).not.toContain('开始对局')
    expect(wrapper.text()).not.toContain('4 人 ·')
    wrapper.unmount()
  })

  it('does not request or display retired audio and video formats', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', '视频讲解'),
          sections: [
            { position: 1, title: '第一节', visualEvidenceRecommended: true },
            { position: 2, title: '第二节', visualEvidenceRecommended: true },
          ],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [section(1, '第一节'), section(2, '第二节')],
        })
      }
      if (path.endsWith('/narration/playback')) {
        return Response.json({
          provider: 'test', durationMillis: 20_000,
          script: {
            id: 'narration-1', status: 'READY', chapters: [
              { position: 1, type: 'SETUP', title: '第一节', supported: true, segments: [{ position: 1, text: '第一节字幕', sourcePages: [1] }] },
              { position: 2, type: 'TURN', title: '第二节', supported: true, segments: [{ position: 1, text: '第二节字幕', sourcePages: [2] }] },
            ],
          },
          cues: [
            { chapterPosition: 1, segmentPosition: 1, startMillis: 0, endMillis: 9_999 },
            { chapterPosition: 2, segmentPosition: 1, startMillis: 10_000, endMillis: 20_000 },
          ],
        })
      }
      if (path.endsWith('/video')) {
        return Response.json({
          id: 'video-1', status: 'READY', durationMillis: 20_000, chapters: [
            { position: 1, type: 'SETUP', title: '第一节', evidenceStatus: 'SUPPORTED', visualKind: 'FLOW_DIAGRAM', visualCaption: '第一节画面', startMillis: 0, endMillis: 9_999, frames: [{ segmentPosition: 1, startMillis: 0, endMillis: 9_999, subtitle: '第一节字幕', sourcePages: [1] }] },
            { position: 2, type: 'TURN', title: '第二节', evidenceStatus: 'SUPPORTED', visualKind: 'FLOW_DIAGRAM', visualCaption: '第二节画面', startMillis: 10_000, endMillis: 20_000, frames: [{ segmentPosition: 1, startMillis: 10_000, endMillis: 20_000, subtitle: '第二节字幕', sourcePages: [2] }] },
          ],
        })
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          CardOcrCapture: true,
          VoiceQuestionCapture: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('audio').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('分章节视频')
    const paths = fetchMock.mock.calls.map(([input]) => String(input))
    expect(paths.some((path) => path.includes('/narration'))).toBe(false)
    expect(paths.some((path) => path.endsWith('/video'))).toBe(false)
    wrapper.unmount()
  })

  it('aborts the full initial bundle and keeps the latest guide when an earlier route resolves late', async () => {
    const pending: Array<{ path: string; resolve: (response: Response) => void }> = []
    const firstSignals: AbortSignal[] = []
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('plan-1')) {
        firstSignals.push(init!.signal!)
        return new Promise<Response>((resolve) => { pending.push({ path, resolve }) })
      }
      if (path === '/api/v1/teaching-plans/plan-2') {
        return Promise.resolve(Response.json(planFixture('plan-2', '第二份规则')))
      }
      if (path === '/api/v1/teaching-plans/plan-2/illustrated-lessons/latest') {
        return Promise.resolve(Response.json({
          id: 'lesson-2', teachingPlanId: 'plan-2', status: 'COMPLETE', sections: [section(1, '第二份讲解')],
        }))
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return Promise.resolve(new Response(null, { status: 404 }))
      if (path === '/api/auth/session') return Promise.resolve(Response.json({ username: 'player', roles: ['USER'] }))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          CardOcrCapture: true,
          VoiceQuestionCapture: true,
        },
      },
    })
    await flushPromises()
    expect(pending).toHaveLength(5)
    expect(firstSignals).toHaveLength(5)

    await router.push('/lesson/plan-2')
    await flushPromises()
    expect(wrapper.text()).toContain('第二份讲解')
    expect(firstSignals.every(signal => signal.aborted)).toBe(true)

    for (const request of pending) request.resolve(staleInitialResponse(request.path))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份讲解')
    expect(wrapper.text()).not.toContain('第一份讲解')
    wrapper.unmount()
  })

  it('uses a fresh controller and recovers when the player retries an initial failure', async () => {
    let planReads = 0
    const readerSignals: AbortSignal[] = []
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('plan-1') && !path.endsWith('/comprehension')) readerSignals.push(init!.signal!)
      if (path === '/api/v1/teaching-plans/plan-1') {
        planReads++
        return planReads === 1
          ? new Response(null, { status: 503 })
          : Response.json(planFixture('plan-1', '恢复后的规则'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '恢复后的讲解')],
        })
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法读取这份讲解')
    expect(readerSignals.slice(0, 5).every(signal => signal.aborted)).toBe(true)
    const firstControllerSignal = readerSignals[0]

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('恢复后的讲解')
    expect(readerSignals[5]).not.toBe(firstControllerSignal)
    expect(readerSignals.slice(5, 10).every(signal => !signal.aborted)).toBe(true)
    wrapper.unmount()
  })

  it.each([
    ['plan', 'Teaching Plan'],
    ['lesson', 'Illustrated Lesson'],
    ['run', 'Assistant Run'],
  ] as const)('rejects a mismatched %s identity before it labels the private reader as authoritative', async (mismatch, _label) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture(mismatch === 'plan' ? 'plan-2' : 'plan-1', 'Identity check'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: mismatch === 'lesson' ? 'plan-2' : 'plan-1',
          status: 'COMPLETE', sections: [section(1, '不应显示')],
        })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(runFixture(mismatch === 'run' ? 'plan-2' : 'plan-1', 'COMPLETED'))
      }
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法读取这份讲解')
    expect(wrapper.text()).not.toContain('不应显示')
    wrapper.unmount()
  })

  it('aborts an active generation poll while offline and resumes from a fresh round after reconnect', async () => {
    let teachingReads = 0
    let lessonReads = 0
    let resolveRun!: (response: Response) => void
    let resolveLesson!: (response: Response) => void
    const pollSignals: AbortSignal[] = []
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Promise.resolve(Response.json(planFixture('plan-1', '联网恢复')))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return Promise.resolve(new Response(null, { status: 404 }))
      if (path.includes('mode=TEACHING')) {
        teachingReads++
        if (teachingReads === 1) return Promise.resolve(Response.json(runFixture('plan-1', 'RETRIEVING')))
        if (teachingReads === 2) {
          pollSignals.push(init!.signal!)
          return new Promise<Response>((resolve) => { resolveRun = resolve })
        }
        return Promise.resolve(Response.json(runFixture('plan-1', 'COMPLETED')))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads++
        if (lessonReads === 1) return Promise.resolve(Response.json(lessonFixture('plan-1', '进行中的讲解', 'INCOMPLETE')))
        if (lessonReads === 2) {
          pollSignals.push(init!.signal!)
          return new Promise<Response>((resolve) => { resolveLesson = resolve })
        }
        return Promise.resolve(Response.json(lessonFixture('plan-1', '恢复后的完整讲解', 'COMPLETE')))
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(pollSignals).toHaveLength(2)

    online.mockReturnValue(false)
    window.dispatchEvent(new Event('offline'))
    await flushPromises()
    expect(pollSignals.every(signal => signal.aborted)).toBe(true)
    expect(wrapper.text()).toContain('当前离线')

    resolveRun(Response.json(runFixture('plan-1', 'RETRIEVING')))
    resolveLesson(Response.json(lessonFixture('plan-1', '迟到讲解', 'INCOMPLETE')))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(teachingReads).toBe(2)
    expect(lessonReads).toBe(2)

    online.mockReturnValue(true)
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()

    expect(teachingReads).toBe(3)
    expect(lessonReads).toBe(4)
    expect(wrapper.text()).toContain('恢复后的完整讲解')
    expect(wrapper.text()).not.toContain('迟到讲解')
    wrapper.unmount()
  })

  it('aborts an active visual-enrichment round on unmount and never schedules another read', async () => {
    let visualReads = 0
    let lessonReads = 0
    let resolveRun!: (response: Response) => void
    let resolveLesson!: (response: Response) => void
    const pollSignals: AbortSignal[] = []
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Promise.resolve(Response.json(planFixture('plan-1', '视觉补全')))
      }
      if (path.includes('mode=TEACHING')) {
        return Promise.resolve(Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run')))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualReads++
        if (visualReads === 1) return Promise.resolve(Response.json(runFixture('plan-1', 'RETRIEVING', 'visual-run')))
        pollSignals.push(init!.signal!)
        return new Promise<Response>((resolve) => { resolveRun = resolve })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads++
        if (lessonReads === 1) return Promise.resolve(Response.json(lessonFixture('plan-1', '已有讲解', 'COMPLETE')))
        pollSignals.push(init!.signal!)
        return new Promise<Response>((resolve) => { resolveLesson = resolve })
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(2_500)
    await flushPromises()
    expect(pollSignals).toHaveLength(2)
    wrapper.unmount()
    expect(pollSignals.every(signal => signal.aborted)).toBe(true)

    resolveRun(Response.json(runFixture('plan-1', 'COMPLETED', 'visual-run')))
    resolveLesson(Response.json(lessonFixture('plan-1', '迟到视觉讲解', 'COMPLETE')))
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(visualReads).toBe(2)
    expect(lessonReads).toBe(2)
  })

  it('keeps the focused English reader separate from the English Q&A entry', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Deep Space'))
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, 'First round')],
        })
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: { AppShell: { template: '<div><slot /></div>' } },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('My illustrated guide')
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('Rule Q&A')
    expect(wrapper.find('#lesson-question-panel').exists()).toBe(false)
    expect(wrapper.text()).toContain('Source: page 1')
    wrapper.unmount()
  })

  it('shows visual crops that finish while the player keeps reading the lesson', async () => {
    let visualRunReads = 0
    let lessonReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture('plan-1', 'Live Visuals'))
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json({
          run: {
            id: 'teaching-run', subjectId: 'plan-1', state: 'COMPLETED',
            createdAt: '2026-07-21T00:00:00Z', completedAt: '2026-07-21T00:00:10Z',
          },
          budget: { usedModelCalls: 1, maxModelCalls: 48 }, activities: [],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads++
        const completed = visualRunReads > 1
        return Response.json({
          run: {
            id: 'visual-run', subjectId: 'plan-1', state: completed ? 'COMPLETED' : 'RETRIEVING',
            createdAt: '2026-07-21T00:00:10Z', completedAt: completed ? '2026-07-21T00:00:20Z' : null,
          },
          budget: { usedModelCalls: completed ? 1 : 0, maxModelCalls: 48 },
          activities: completed ? [{
            sequence: 1, type: 'VALIDATION', operation: 'visualSection|1', summary: '已加入局部规则书截图',
            outcome: 'SUCCEEDED', latencyMs: 0, occurredAt: '2026-07-21T00:00:20Z',
          }] : [],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads++
        const enriched = section(1, '第一节')
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: lessonReads === 1
            ? [{ ...enriched, steps: enriched.steps.filter((step) => step.kind !== 'VISUAL') }]
            : [enriched],
        })
      }
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: {
        plugins: [router],
        stubs: { AppShell: { template: '<div><slot /></div>' } },
      },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="lesson-visual-detail"] img[alt*="主棋盘区域"]').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(2500)
    await flushPromises()

    expect(wrapper.get('[data-testid="lesson-visual-detail"] img[alt*="主棋盘区域"]').attributes('src'))
      .toContain('/pages/1/image/crop?x=100&y=200&width=500&height=400')
    expect(fetchMock.mock.calls.map(([input]) => String(input))
      .filter((path) => path.endsWith('/illustrated-lessons/latest'))).toHaveLength(2)
    wrapper.unmount()
  })

})

function planFixture(id: string, gameTitle: string) {
  return {
    id,
    documentVersionId: `version-${id}`,
    gameTitle,
    premise: '测试讲解切换',
    sections: [{ position: 1, title: '第一节', visualEvidenceRecommended: true }],
  }
}

function lessonFixture(
  teachingPlanId: string,
  title: string,
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE' = 'COMPLETE',
) {
  return {
    id: `lesson-${teachingPlanId}`,
    teachingPlanId,
    status,
    sections: [section(1, title)],
  }
}

function runFixture(subjectId: string, state: string, id = 'teaching-run') {
  return {
    run: {
      id,
      subjectId,
      state,
      createdAt: '2026-07-21T00:00:00Z',
      updatedAt: '2026-07-21T00:01:00Z',
      completedAt: state === 'COMPLETED' ? '2026-07-21T00:01:00Z' : null,
      lastErrorCode: null,
    },
    budget: { usedModelCalls: 1, maxModelCalls: 48 },
    activities: [],
  }
}

function staleInitialResponse(path: string) {
  if (path === '/api/v1/teaching-plans/plan-1') {
    return Response.json(planFixture('plan-1', '第一份规则'))
  }
  if (path.endsWith('/illustrated-lessons/latest')) {
    return Response.json(lessonFixture('plan-1', '第一份讲解'))
  }
  if (path.includes('/assistant-runs/latest')) return new Response(null, { status: 404 })
  return new Response(null, { status: 404 })
}

function section(position: number, title: string) {
  return {
    position,
    topicKey: `topic-${position}`,
    coverageTags: position === 1 ? ['setup'] : ['core_loop'],
    title,
    required: true,
    evidenceStatus: 'SUPPORTED',
    visualKind: 'REFERENCE_CARD',
    visualCaption: '规则书原页',
    visualSourcePages: [position],
    visualSourceChunkIds: [`chunk-${position}`],
    steps: [
      {
        position: 1, heading: '先理解', kind: 'UNDERSTAND', text: `${title}的核心规则`,
        sourcePages: [position], visualFocus: null,
      },
      {
        position: 2, heading: '照着做', kind: 'DO', text: title,
        sourcePages: [position], visualFocus: null,
      },
      {
        position: 3, heading: '对照主棋盘', kind: 'VISUAL', text: '找到主棋盘的拼接区域',
        sourcePages: [position],
        visualFocus: {
          pageNumber: position,
          label: '主棋盘区域',
          visibleDescription: '主棋盘中央有三条相连的行动轨道。',
          x: 100,
          y: 200,
          width: 500,
          height: 400,
        },
      },
      {
        position: 4, heading: '别放反', kind: 'WATCH', text: '确认组件方向',
        sourcePages: [position], visualFocus: null,
      },
      {
        position: 5, heading: '自己检查', kind: 'CHECK', text: '不看规则书复述一次',
        sourcePages: [position], visualFocus: null,
      },
    ],
  }
}

function catalogPresentationFixture(gameName: string) {
  return {
    editionId: 'edition-1', gameName, editionName: `${gameName} edition`, language: 'zh-CN',
    publicationYear: 2024, bggId: 42, thumbnailUrl: 'https://example.test/catalog-cover.jpg',
    minPlayers: 1, maxPlayers: 5, playingTimeMinutes: 60, minimumAge: 10,
    bggUrl: 'https://boardgamegeek.com/boardgame/42',
  }
}

function createMemoryRouter() {
  const Empty = { template: '<div />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: Empty },
      { path: '/catalog', name: 'catalog', component: Empty },
      { path: '/teach', name: 'teach', component: Empty },
      { path: '/lessons', name: 'lessons', component: Empty },
      { path: '/lesson/:planId', name: 'lesson', component: LessonView },
      { path: '/lesson/:planId/questions', name: 'lesson-questions', component: Empty },
      { path: '/read/:planId', name: 'public-lesson', component: Empty },
      { path: '/table/:planId', name: 'table-mode', component: Empty },
      { path: '/account', name: 'account', component: Empty },
      { path: '/settings/models', name: 'model-settings', component: Empty },
      { path: '/login', name: 'login', component: Empty },
    ],
  })
}
