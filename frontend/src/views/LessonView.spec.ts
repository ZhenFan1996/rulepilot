import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonView from './LessonView.vue'
import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'
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
    expect(wrapper.text()).toContain('正在依据规则书编写第 1 章“先摆主板”')
    expect(wrapper.text()).toContain('后台已处理 0/2 节')
    expect(wrapper.text()).not.toContain('模型调用')
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

  it('never presents an empty terminal lesson as readable or complete', async () => {
    let teachingReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture('plan-1', 'Empty terminal guide'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('mode=TEACHING')) {
        teachingReads += 1
        return Response.json(runFixture('plan-1', teachingReads > 1 ? 'COMPLETED' : 'RETRIEVING'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-empty', teachingPlanId: 'plan-1',
          status: teachingReads > 1 ? 'COMPLETE' : 'INCOMPLETE', sections: [],
        })
      }
      if (path === '/api/auth/session') return Response.json({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lesson/plan-1')
    await router.isReady()
    const wrapper = mount(LessonView, {
      global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('需要处理')
    expect(wrapper.text()).toContain('还没有可读章节')
    expect(wrapper.text()).not.toContain('讲解完成')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')
    wrapper.unmount()
  })

  it('does not present or poll an authoritative CANCELLED teaching run as active generation', async () => {
    let teachingRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'Cancelled guide'),
          sections: [{ position: 1, title: '已保留章节', visualEvidenceRecommended: false }],
        })
      }
      if (path.includes('mode=TEACHING')) {
        teachingRunReads += 1
        const snapshot = runFixture('plan-1', 'CANCELLED')
        return Response.json({
          ...snapshot,
          run: { ...snapshot.run, lastErrorCode: 'AGENT_CANCELLED' },
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-cancelled', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [section(1, '已保留章节')],
        })
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

    expect(teachingRunReads).toBe(1)
    expect(wrapper.text()).toContain('已保留章节')
    expect(wrapper.text()).not.toContain('整本仍在后台生成')
    const cancelledStatus = wrapper.get('[data-testid="player-work-status"]')
    expect(cancelledStatus.text()).toBe('已取消')
    expect(cancelledStatus.attributes('data-player-work-outcome')).toBe('cancelled')
    expect(cancelledStatus.attributes('data-player-work-readiness')).toBe('usable')
    expect(wrapper.text()).toContain('本轮讲解生成已取消')
    expect(wrapper.text()).toContain('已保留 1 章可读讲解草稿')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()

    expect(teachingRunReads).toBe(1)
    expect(wrapper.text()).not.toContain('整本仍在后台生成')
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

  it('keeps a completed cited draft readable without promoting it to a fully complete guide', async () => {
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'Cited draft'),
          sections: [{ position: 1, title: 'Readable draft', visualEvidenceRecommended: false }],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED'))
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-cited', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [section(1, 'Readable draft', 'CITED_DRAFT')],
        })
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

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('Base guide ready')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(status.attributes('data-player-work-outcome')).toBe('none')
    expect(wrapper.text()).toContain('readable guide draft')
    expect(wrapper.text()).toContain('Additional content review is not complete')
    expect(wrapper.text()).not.toContain('Guide complete')
    expect(wrapper.text()).not.toContain('Every chapter is loaded')
    expect(status.element.closest('[role="status"]')?.classList.contains('bg-amber-50')).toBe(true)
    wrapper.unmount()
  })

  it('does not call an insufficient-evidence placeholder chapter readable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'Insufficient guide'),
          sections: [{ position: 1, title: '证据不足', visualEvidenceRecommended: false }],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'INSUFFICIENT_EVIDENCE'))
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-insufficient', teachingPlanId: 'plan-1', status: 'INCOMPLETE',
          sections: [section(1, '暂时跳过', 'INSUFFICIENT_EVIDENCE')],
        })
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

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('需要处理')
    expect(status.attributes('data-player-work-readiness')).toBe('unavailable')
    expect(status.attributes('data-player-work-outcome')).toBe('needs-action')
    expect(wrapper.text()).toContain('还没有可读章节')
    expect(wrapper.text()).not.toContain('基础讲解可读')
    wrapper.unmount()
  })

  it.each(['DEGRADED', 'INSUFFICIENT_EVIDENCE'])('keeps the readable subset usable when a %s run ends with local evidence gaps', async (terminalState) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'Partial guide'),
          sections: [
            { position: 1, title: '可读章节', visualEvidenceRecommended: false },
            { position: 2, title: '证据不足', visualEvidenceRecommended: false },
          ],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', terminalState))
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-partial', teachingPlanId: 'plan-1', status: 'INCOMPLETE',
          sections: [
            section(1, '可读章节', 'SUPPORTED'),
            section(2, '暂时跳过', 'INSUFFICIENT_EVIDENCE'),
          ],
        })
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

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('基础讲解可读')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(status.attributes('data-player-work-outcome')).toBe('none')
    expect(wrapper.text()).toContain('已保留 1 章可读讲解草稿')
    expect(wrapper.text()).toContain('没有作为完整讲解发布')
    expect(wrapper.text()).not.toContain('讲解完成')
    expect(wrapper.text()).not.toContain('需要处理')
    wrapper.unmount()
  })

  it('uses the same failed terminal presentation after an active run preserves a cited draft', async () => {
    let runReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'Failed guide'),
          sections: [{ position: 1, title: '可读草稿', visualEvidenceRecommended: false }],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('mode=TEACHING')) {
        runReads += 1
        return Response.json(runFixture('plan-1', runReads > 1 ? 'FAILED' : 'VERIFYING_EVIDENCE'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-failed', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [section(1, '可读草稿', 'CITED_DRAFT')],
        })
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

    expect(wrapper.text()).toContain('完整基础讲解已经可用')
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('失败')
    expect(status.attributes('data-player-work-outcome')).toBe('failed')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(wrapper.text()).toContain('本轮讲解生成失败')
    expect(wrapper.text()).toContain('已保留 1 章可读讲解草稿')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')
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
    expect(pending).toHaveLength(4)
    expect(firstSignals).toHaveLength(4)

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
    let comprehensionReads = 0
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
          sections: lessonReads < 3
            ? [{ ...enriched, steps: enriched.steps.filter((step) => step.kind !== 'VISUAL') }]
            : [enriched],
        })
      }
      if (path.endsWith('/comprehension')) {
        comprehensionReads += 1
        return Response.json({ lessonId: 'lesson-1', tasks: [], visualAids: [] })
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

    expect(visualRunReads).toBe(2)
    expect(wrapper.find('[data-testid="lesson-visual-detail"] img[alt*="主棋盘区域"]').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(2500)
    await flushPromises()

    expect(wrapper.get('[data-testid="lesson-visual-detail"] img[alt*="主棋盘区域"]').attributes('src'))
      .toContain('/pages/1/image/crop?x=100&y=200&width=500&height=400')
    expect(comprehensionReads).toBe(2)

    await vi.advanceTimersByTimeAsync(2500)
    await flushPromises()
    expect(fetchMock.mock.calls.map(([input]) => String(input))
      .filter((path) => path.endsWith('/illustrated-lessons/latest'))).toHaveLength(4)
    expect(visualRunReads).toBe(2)
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
  })

  it('keeps a ready English lesson displayed when a visual crop arrives after localization', async () => {
    setLocale('en')
    let visualRunReads = 0
    let lessonReads = 0
    let localizationReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture('plan-1', 'Localized Visuals'))
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json(runFixture(
          'plan-1',
          visualRunReads === 1 ? 'RETRIEVING' : 'COMPLETED',
          'visual-run',
        ))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads += 1
        const source = section(1, '中文第一节')
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: lessonReads < 3
            ? [{ ...source, steps: source.steps.filter(step => step.kind !== 'VISUAL') }]
            : [source],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest/localizations/en')) {
        localizationReads += 1
        const localized = section(1, 'English first chapter')
        const translatedSteps = localized.steps
          .filter(step => lessonReads >= 3 || step.kind !== 'VISUAL')
          .map(step => step.kind === 'VISUAL'
            ? {
                ...step,
                heading: 'Compare the board',
                text: 'Find the joined board area.',
                visualFocus: step.visualFocus
                  ? {
                      ...step.visualFocus,
                      label: 'Recovered board region',
                      visibleDescription: 'Three connected action tracks cross the centre of the board.',
                    }
                  : null,
              }
            : { ...step, heading: `English ${step.heading}`, text: `English ${step.text}` })
        return Response.json({
          language: 'EN',
          status: 'READY',
          lesson: {
            id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
            sections: [{ ...localized, title: 'English first chapter', steps: translatedSteps }],
          },
          failureCode: null,
        })
      }
      if (path.endsWith('/comprehension')) {
        return Response.json({ lessonId: 'lesson-1', tasks: [], visualAids: [] })
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

    expect(wrapper.text()).toContain('English first chapter')
    expect(wrapper.text()).not.toContain('中文第一节')
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(2_500)
    await flushPromises()
    expect(visualRunReads).toBe(2)
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)

    await vi.advanceTimersByTimeAsync(2_500)
    await flushPromises()

    expect(localizationReads).toBe(3)
    expect(wrapper.text()).toContain('English first chapter')
    expect(wrapper.text()).toContain('Three connected action tracks cross the centre of the board.')
    expect(wrapper.text()).not.toContain('中文第一节')
    expect(wrapper.text()).not.toContain('The English guide could not be prepared')
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src'))
      .toContain('/pages/1/image/crop?x=100&y=200&width=500&height=400')
    wrapper.unmount()
  })

  it('keeps the completed text lesson readable when visual enrichment fails', async () => {
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture('plan-1', 'Text Survives'))
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json({
          ...runFixture('plan-1', 'FAILED', 'visual-run'),
          run: {
            ...runFixture('plan-1', 'FAILED', 'visual-run').run,
            lastErrorCode: 'VISUAL_ENRICHMENT_FAILED',
          },
          activities: [],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        const readable = section(1, '文字讲解仍可读')
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [{
            ...readable,
            steps: readable.steps.filter((step) => step.kind !== 'VISUAL'),
          }],
        })
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

    expect(wrapper.text()).toContain('文字讲解仍可读')
    expect(wrapper.text()).toContain('局部配图没有完成')
    expect(wrapper.text()).toContain('已发布的文字讲解仍可完整阅读')
    expect(wrapper.find('[data-testid="private-lesson-reader"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    wrapper.unmount()
  })

  it('bounds visual transport failures, keeps the lesson readable, and resumes only after an explicit retry', async () => {
    let visualRunReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Bounded Visuals'))
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        if (!recovered) return new Response(null, { status: 503 })
        return Response.json(runFixture('plan-1', 'COMPLETED', 'visual-run'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture('plan-1', '始终可读的文字'))
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

    expect(visualRunReads).toBe(1)
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(2)
    await vi.advanceTimersByTimeAsync(6_000)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(wrapper.text()).toContain('始终可读的文字')
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')
    const readsAtStop = visualRunReads
    await vi.advanceTimersByTimeAsync(60_000)
    expect(visualRunReads).toBe(readsAtStop)

    recovered = true
    await wrapper.findAll('button').find(button => button.text() === '重试配图状态')!.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(4)
    expect(wrapper.text()).not.toContain('暂时无法确认最新配图状态')
    wrapper.unmount()
  })

  it('makes exhausted visual discovery visible and recovers a visual run that appears after retry', async () => {
    let visualRunReads = 0
    let visualReady = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Late Visual Run'))
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return visualReady
          ? Response.json(runFixture('plan-1', 'COMPLETED', 'visual-run'))
          : new Response(null, { status: 404 })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture('plan-1', '文字讲解不等待配图'))
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

    expect(visualRunReads).toBe(1)
    expect(wrapper.text()).toContain('文字讲解不等待配图')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(visualRunReads).toBe(2)
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')
    const retry = wrapper.findAll('button').find(button => button.text() === '重试配图状态')
    expect(retry).toBeDefined()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(visualRunReads).toBe(2)

    visualReady = true
    await retry!.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(wrapper.text()).toContain('这次没有找到可靠的局部图示')
    expect(wrapper.text()).not.toContain('暂时无法确认最新配图状态')
    wrapper.unmount()
  })

  it('does not keep readable text loading behind a visual status request that never settles', async () => {
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Visual Deadline'))
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return await new Promise<Response>(() => undefined)
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture('plan-1', '先读文字再等配图'))
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

    expect(visualRunReads).toBe(1)
    expect(wrapper.find('[data-testid="private-lesson-reader"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('先读文字再等配图')
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('规则答疑')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')
    expect(wrapper.findAll('button').some(button => button.text() === '重试配图状态')).toBe(false)

    await vi.advanceTimersByTimeAsync(3_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(6_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(wrapper.findAll('button').some(button => button.text() === '重试配图状态')).toBe(true)
    expect(wrapper.text()).toContain('先读文字再等配图')
    wrapper.unmount()
  })

  it('bounds a visual terminal settlement whose lesson snapshot never settles and recovers manually', async () => {
    let visualRunReads = 0
    let lessonReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json(planFixture('plan-1', 'Pending Visual Settlement'))
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json(runFixture(
          'plan-1',
          visualRunReads === 1 ? 'RETRIEVING' : 'COMPLETED',
          'visual-run',
        ))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads > 1 && !recovered) {
          return await new Promise<Response>(() => undefined)
        }
        return Response.json(lessonFixture(
          'plan-1',
          recovered ? '手动恢复后的文字终态' : '视觉对账前的可读文字',
        ))
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

    expect(visualRunReads).toBe(1)
    expect(wrapper.text()).toContain('视觉对账前的可读文字')
    expect(wrapper.get('a[href="/lesson/plan-1/questions"]').text()).toContain('规则答疑')

    await vi.advanceTimersByTimeAsync(2_500)
    await flushPromises()
    expect(visualRunReads).toBe(2)
    expect(lessonReads).toBe(2)
    expect(wrapper.text()).toContain('视觉对账前的可读文字')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')

    await vi.advanceTimersByTimeAsync(4_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(8_000)
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()

    expect(visualRunReads).toBe(4)
    expect(lessonReads).toBe(4)
    expect(wrapper.text()).toContain('视觉对账前的可读文字')
    expect(wrapper.findAll('button').some(button => button.text() === '重试配图状态')).toBe(true)
    const readsAtStop = lessonReads
    await vi.advanceTimersByTimeAsync(30_000)
    expect(lessonReads).toBe(readsAtStop)

    recovered = true
    await wrapper.findAll('button').find(button => button.text() === '重试配图状态')!.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(visualRunReads).toBe(5)
    expect(lessonReads).toBe(5)
    expect(wrapper.text()).toContain('手动恢复后的文字终态')
    expect(wrapper.text()).not.toContain('暂时无法确认最新配图状态')
    wrapper.unmount()
  })

  it('bounds terminal visual lesson-settling failures instead of polling a stale terminal snapshot forever', async () => {
    let lessonReads = 0
    let visualRunReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Settling Budget'))
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json(runFixture('plan-1', 'COMPLETED', 'visual-run'))
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads > 1 && !recovered) return new Response(null, { status: 503 })
        return Response.json(lessonFixture('plan-1', recovered ? '对账恢复' : '终态文字'))
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

    expect(visualRunReads).toBe(1)
    await vi.advanceTimersByTimeAsync(250 + 4_000 + 8_000)
    await flushPromises()
    expect(lessonReads).toBe(4)
    const readsAtStop = lessonReads
    await vi.advanceTimersByTimeAsync(60_000)
    expect(lessonReads).toBe(readsAtStop)

    recovered = true
    await wrapper.findAll('button').find(button => button.text() === '重试配图状态')!.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(wrapper.text()).toContain('对账恢复')
    expect(visualRunReads).toBe(1)
    wrapper.unmount()
  })

  it('preserves the authentication boundary when visual status returns 401', async () => {
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Private Visuals'))
      if (path.includes('mode=TEACHING')) return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return new Response(null, { status: 401 })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture('plan-1', '身份边界内的文字'))
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

    expect(loginRequired).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('身份边界内的文字')
    expect(wrapper.text()).toContain('请先登录')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(visualRunReads).toBe(1)
    window.removeEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    wrapper.unmount()
  })

  it.each([401, 403])('stops active lesson polling after a %s identity response and retries only on request', async (status) => {
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    let teachingRunReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(planFixture('plan-1', 'Private Generation'))
      if (path.includes('mode=TEACHING')) {
        teachingRunReads += 1
        if (teachingRunReads === 1) return Response.json(runFixture('plan-1', 'RETRIEVING', 'teaching-run'))
        if (!recovered) return new Response(null, { status })
        return Response.json(runFixture('plan-1', 'COMPLETED', 'teaching-run'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json(lessonFixture(
          'plan-1',
          recovered ? '登录后接住终态' : '认证前已发布文字',
          recovered ? 'COMPLETE' : 'INCOMPLETE',
        ))
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

    try {
      await vi.advanceTimersByTimeAsync(1_500)
      await flushPromises()

      expect(loginRequired).toHaveBeenCalledOnce()
      expect(wrapper.get('[data-testid="lesson-generation-auth-stopped"]').text()).toContain('请先登录')
      expect(wrapper.text()).not.toContain('暂时没有取得最新章节，正在自动重试')
      expect(wrapper.text()).toContain('认证前已发布文字')
      const readsAtStop = teachingRunReads
      await vi.advanceTimersByTimeAsync(30_000)
      await flushPromises()
      expect(teachingRunReads).toBe(readsAtStop)
      expect(loginRequired).toHaveBeenCalledOnce()

      recovered = true
      await wrapper.get('[data-testid="lesson-generation-auth-stopped"] button').trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      await flushPromises()

      expect(teachingRunReads).toBe(readsAtStop + 1)
      expect(wrapper.find('[data-testid="lesson-generation-auth-stopped"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('登录后接住终态')
      expect(wrapper.text()).toContain('讲解已经生成完成')
    } finally {
      window.removeEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
      wrapper.unmount()
    }
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

function section(
  position: number,
  title: string,
  evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE' = 'SUPPORTED',
) {
  return {
    position,
    topicKey: `topic-${position}`,
    coverageTags: position === 1 ? ['setup'] : ['core_loop'],
    title,
    required: true,
    evidenceStatus,
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
