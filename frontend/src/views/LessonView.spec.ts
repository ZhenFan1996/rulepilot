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
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4,
          beginnerCount: 3, durationMinutes: 25, gameTitle: 'SETI', premise: '寻找生命',
          sections: [
            { position: 1, title: '先摆主板', visualEvidenceRecommended: true },
            { position: 2, title: '开始第一轮', visualEvidenceRecommended: false },
          ],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('/api/v1/assistant-runs/latest')) {
        runReads++
        if (runReads === 1) throw new TypeError('temporary run status failure')
        return Response.json({
          run: {
            id: 'run-1',
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
          id: 'lesson-1', status: lessonReads >= 3 ? 'COMPLETE' : 'INCOMPLETE', sections,
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
    expect(wrapper.text()).toContain('问规则书')
    expect(wrapper.text()).toContain('没有保存人数、轮次或实时局面')
    expect(wrapper.find('#lesson-question-panel').element.tagName).toBe('SECTION')
    expect(wrapper.text()).not.toContain('开始对局')
    expect(wrapper.text()).not.toContain('4 人 ·')
    expect(wrapper.find('img[alt*="主棋盘区域"]').attributes('src'))
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
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.text()).not.toContain('整本仍在后台生成')
    expect(qualityReads).toBe(1)
    expect(wrapper.text()).toContain('逐张看看这些规则书裁剪图')
    expect(wrapper.text()).toContain('焦点图有帮助 1 / 1（100%）')
    expect(wrapper.find('img[alt*="行动区"]').attributes('src'))
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
    wrapper.unmount()
  })

  it('lets the player use a complete cited draft while factual review remains active', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4,
          beginnerCount: 3, durationMinutes: 25, gameTitle: 'SETI', premise: '寻找生命',
          sections: [{ position: 1, title: '先摆主板', visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) return new Response(null, { status: 404 })
      if (path.includes('/api/v1/assistant-runs/latest')) {
        return Response.json({
          run: {
            id: 'run-1', state: 'VERIFYING_EVIDENCE', createdAt: '2026-07-21T00:00:00Z',
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
          id: 'lesson-1', status: 'DRAFT_READY',
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
    expect(wrapper.text()).toContain('问这一章')
    expect(wrapper.text()).not.toContain('开始对局')
    expect(wrapper.text()).not.toContain('4 人 ·')
    wrapper.unmount()
  })

  it('keeps a video chapter selected while an older audio timeupdate arrives', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
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
        return Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [section(1, '第一节'), section(2, '第二节')] })
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

    await wrapper.findAll('button').find((button) => button.text() === '视频')!.trigger('click')
    const videoPanel = wrapper.get('[aria-label="分章节视频"]')
    await videoPanel.findAll('button').find((button) => button.text().includes('2. 第二节'))!.trigger('click')
    await flushPromises()
    Object.defineProperty(wrapper.get('audio').element, 'currentTime', { configurable: true, value: 0 })
    await wrapper.get('audio').trigger('timeupdate')

    expect(wrapper.text()).toContain('第 2 章 · 第二节')
    expect(videoPanel.text()).toContain('第二节字幕')
    wrapper.unmount()
  })

  it('keeps the latest private guide when an earlier navigation resolves late', async () => {
    let resolveFirstPlan: ((response: Response) => void) | undefined
    let resolveFirstLesson: ((response: Response) => void) | undefined
    const fetchMock = vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return new Promise<Response>((resolve) => { resolveFirstPlan = resolve })
      }
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
        return new Promise<Response>((resolve) => { resolveFirstLesson = resolve })
      }
      if (path === '/api/v1/teaching-plans/plan-2') {
        return Promise.resolve(Response.json(planFixture('plan-2', '第二份规则')))
      }
      if (path === '/api/v1/teaching-plans/plan-2/illustrated-lessons/latest') {
        return Promise.resolve(Response.json({ id: 'lesson-2', status: 'COMPLETE', sections: [section(1, '第二份讲解')] }))
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

    await router.push('/lesson/plan-2')
    await flushPromises()
    expect(wrapper.text()).toContain('第二份讲解')

    resolveFirstPlan!(Response.json(planFixture('plan-1', '第一份规则')))
    resolveFirstLesson!(Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [section(1, '第一份讲解')] }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份讲解')
    expect(wrapper.text()).not.toContain('第一份讲解')
    wrapper.unmount()
  })

  it('does not attach an answer from the previous guide after navigating away', async () => {
    let resolveAnswer: ((response: Response) => void) | undefined
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(planFixture('plan-1', '第一份规则')))
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
        return Promise.resolve(Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [section(1, '第一份讲解')] }))
      }
      if (path === '/api/v1/teaching-plans/plan-2') return Promise.resolve(Response.json(planFixture('plan-2', '第二份规则')))
      if (path === '/api/v1/teaching-plans/plan-2/illustrated-lessons/latest') {
        return Promise.resolve(Response.json({ id: 'lesson-2', status: 'COMPLETE', sections: [section(1, '第二份讲解')] }))
      }
      if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        return new Promise<Response>((resolve) => { resolveAnswer = resolve })
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

    await wrapper.get('#lesson-question').setValue('第一份规则里的问题')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()

    await router.push('/lesson/plan-2')
    await flushPromises()
    resolveAnswer!(Response.json({
      assistantRunId: 'answer-run-1',
      answer: {
        status: 'ANSWERED', shortVerdict: '陈旧答案', explanation: '不应出现在第二份讲解中。', citations: [], exceptions: [],
        confidence: 'HIGH', official: false, confirmedRulingId: null, confirmedRulingVersion: null, clarification: null,
      },
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二份讲解')
    expect(wrapper.text()).not.toContain('陈旧答案')
    wrapper.unmount()
  })

  it('does not attach an answer to a different chapter in the same guide', async () => {
    let resolveAnswer: ((response: Response) => void) | undefined
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Promise.resolve(Response.json({
          ...planFixture('plan-1', '同一份规则'),
          sections: [
            { position: 1, title: '第一节', visualEvidenceRecommended: true },
            { position: 2, title: '第二节', visualEvidenceRecommended: false },
          ],
        }))
      }
      if (path === '/api/v1/teaching-plans/plan-1/illustrated-lessons/latest') {
        return Promise.resolve(Response.json({
          id: 'lesson-1', status: 'COMPLETE', sections: [section(1, '第一节'), section(2, '第二节')],
        }))
      }
      if (path === '/api/auth/csrf') return Promise.resolve(Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }))
      if (path.endsWith('/answers') && init?.method === 'POST') {
        return new Promise<Response>((resolve) => { resolveAnswer = resolve })
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

    await wrapper.get('#lesson-question').setValue('第一节的问题')
    await wrapper.get('#lesson-question-panel form').trigger('submit')
    await flushPromises()

    const secondChapter = wrapper.findAll('button').find((button) => button.text().trim() === '第 2 章 · 第二节')
    await secondChapter!.trigger('click')
    expect(wrapper.text()).toContain('第 2 章 · 第二节')

    resolveAnswer!(Response.json({
      assistantRunId: 'answer-run-1',
      answer: {
        status: 'ANSWERED', shortVerdict: '陈旧章节答案', explanation: '不应出现在第二节中。', citations: [], exceptions: [],
        confidence: 'HIGH', official: false, confirmedRulingId: null, confirmedRulingVersion: null, clarification: null,
      },
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('第二节')
    expect(wrapper.text()).not.toContain('陈旧章节答案')
    wrapper.unmount()
  })

  it('sends an English grounded example request from an English reader', async () => {
    setLocale('en')
    let answerRequest: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...planFixture('plan-1', 'First game'),
          sections: [{ position: 1, title: 'First round', visualEvidenceRecommended: false }],
        })
      }
      if (path.endsWith('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [section(1, 'First round')] })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/answers') && init?.method === 'POST') {
        answerRequest = JSON.parse(String(init.body)) as Record<string, unknown>
        return new Response(null, { status: 503 })
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
        stubs: { AppShell: { template: '<div><slot /></div>' }, CardOcrCapture: true, VoiceQuestionCapture: true },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('← My guides')
    expect(wrapper.text()).toContain('Read publicly')
    expect(wrapper.text()).toContain('My illustrated guide')
    expect(wrapper.text()).toContain('it does not save player count, round, or live table state')
    expect(wrapper.text()).not.toContain('Start a game')
    expect(wrapper.text()).toContain('Guide diagnostics are unavailable, but you can keep reading.')
    expect(wrapper.text()).toContain('Audio is unavailable, so the complete reading guide is still here.')
    expect(wrapper.text()).toContain('Video is unavailable. You can still use reading or audio.')
    expect(wrapper.text()).toContain('Rulebook pages 1')
    await wrapper.findAll('button').find((button) => button.text() === 'Chapter 1 · First round')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === 'Walk through an example')!.trigger('click')
    await flushPromises()

    expect(answerRequest).toMatchObject({
      question: 'Using the rules for “First round”, walk through one concrete, legal table example.',
      currentLessonSection: 'topic-1 First round setup',
      learningIntent: 'EXAMPLE',
      language: 'en',
    })
    expect(answerRequest).not.toHaveProperty('playerCount')
    wrapper.unmount()
  })
})

function planFixture(id: string, gameTitle: string) {
  return {
    id,
    documentVersionId: `version-${id}`,
    playerCount: 4,
    beginnerCount: 2,
    durationMinutes: 30,
    gameTitle,
    premise: '测试讲解切换',
    sections: [{ position: 1, title: '第一节', visualEvidenceRecommended: true }],
  }
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
        visualFocus: { pageNumber: position, label: '主棋盘区域', x: 100, y: 200, width: 500, height: 400 },
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
      { path: '/read/:planId', name: 'public-lesson', component: Empty },
      { path: '/table/:planId', name: 'table-mode', component: Empty },
      { path: '/account', name: 'account', component: Empty },
      { path: '/settings/models', name: 'model-settings', component: Empty },
      { path: '/login', name: 'login', component: Empty },
    ],
  })
}
