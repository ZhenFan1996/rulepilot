import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import RecommendationLessonDialog from './RecommendationLessonDialog.vue'

const plan = {
  id: 'plan-1', documentVersionId: 'document-1', gameTitle: '展翅翱翔', premise: '先建立目标，再按回合练习。',
  sections: [
    { position: 1, title: '目标', visualEvidenceRecommended: false },
    { position: 2, title: '回合', visualEvidenceRecommended: false },
    { position: 3, title: '计分', visualEvidenceRecommended: false },
  ],
}

type EvidenceStatus = 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'

function section(position: number, title: string, evidenceStatus: EvidenceStatus = 'SUPPORTED') {
  return {
    position, topicKey: `TOPIC_${position}`, coverageTags: [], title, required: true, evidenceStatus,
    visualKind: 'FLOW_DIAGRAM', visualCaption: '', visualSourcePages: [position], visualSourceChunkIds: [`chunk-${position}`],
    steps: [{ position: 1, heading: title, kind: 'DO', text: `${title}内容`, sourcePages: [position], visualFocus: null }],
  }
}

function run(state: string, updatedAt: string) {
  return {
    run: { id: 'run-1', subjectId: 'plan-1', state, createdAt: '2026-08-10T00:00:00Z', updatedAt, completedAt: state === 'COMPLETED' ? updatedAt : null, lastErrorCode: null },
    budget: { usedModelCalls: 1, maxModelCalls: 10 }, activities: [],
  }
}

const ChapterListStub = defineComponent({
  name: 'LessonChapterList',
  props: { sections: { type: Array, default: () => [] } },
  template: '<div data-testid="chapter-list-stub">{{ sections.map(item => item.title).join("|") }}</div>',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(settle => { resolve = settle })
  return { promise, resolve }
}

function lesson(title = '目标', planId = 'plan-1') {
  return {
    id: `lesson-${planId}`,
    teachingPlanId: planId,
    status: 'DRAFT_READY' as const,
    sections: [section(1, title)],
  }
}

function planFor(id: string, gameTitle: string) {
  return { ...plan, id, gameTitle }
}

function runFor(planId: string, state = 'RUNNING') {
  return {
    ...run(state, '2026-08-10T00:01:00Z'),
    run: { ...run(state, '2026-08-10T00:01:00Z').run, subjectId: planId },
  }
}

describe('RecommendationLessonDialog', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('paints a same-plan readable snapshot immediately while authoritative reads are pending', async () => {
    const pending = new Promise<Response>(() => undefined)
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      signals.push(options!.signal!)
      return pending
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: {
        open: true,
        planId: 'plan-1',
        initialPlan: plan,
        initialLesson: lesson('首节立即可读'),
      },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('正在打开已生成的讲解')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('首节立即可读')
    expect(signals).toHaveLength(3)

    await wrapper.setProps({ open: false })
    expect(signals.every(signal => signal.aborted)).toBe(true)
    wrapper.unmount()
  })

  it('keeps a readable seed and shows refresh warning when initial reconciliation fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) =>
      String(input).endsWith('/plan-1')
        ? new Response(null, { status: 503 })
        : new Promise<Response>(() => undefined)))
    const wrapper = mount(RecommendationLessonDialog, {
      props: {
        open: true,
        planId: 'plan-1',
        initialPlan: plan,
        initialLesson: lesson('仍可阅读'),
      },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('暂时无法刷新最新章节')
    expect(wrapper.text()).not.toContain('讲解暂时无法打开')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('仍可阅读')
    wrapper.unmount()
  })

  it('rejects a readable seed whose lesson belongs to another plan', async () => {
    const pending = new Promise<Response>(() => undefined)
    vi.stubGlobal('fetch', vi.fn(() => pending))
    const wrapper = mount(RecommendationLessonDialog, {
      props: {
        open: true,
        planId: 'plan-1',
        initialPlan: plan,
        initialLesson: lesson('错误首节', 'plan-other'),
      },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('正在打开已生成的讲解')
    expect(wrapper.text()).not.toContain('错误首节')
    wrapper.unmount()
  })

  it('keeps readable chapters while polling and rejects a stale response with fewer chapters', async () => {
    let lessonRequest = 0
    let runRequest = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        lessonRequest += 1
        if (lessonRequest === 1) return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY', sections: [section(1, '目标')] })
        if (lessonRequest === 2) return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY', sections: [section(1, '目标'), section(2, '回合')] })
        if (lessonRequest === 3) return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY', sections: [section(1, '目标')] })
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '目标'), section(2, '回合'), section(3, '计分')] })
      }
      if (path.includes('/assistant-runs/latest')) {
        runRequest += 1
        return Response.json(run(runRequest >= 4 ? 'COMPLETED' : 'RUNNING', `2026-08-10T00:0${runRequest}:00Z`))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="recommendation-lesson-backdrop"]').classes()).toContain('z-[100]')
    expect(wrapper.get('[data-testid="recommendation-lesson-surface"]').attributes('style'))
      .toContain('background-color: var(--color-canvas); opacity: 1')
    expect(wrapper.text()).toContain('已有 1 / 3 章完成引用、结构与数量校验')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标|回合')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标|回合')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(wrapper.text()).toContain('完整讲解已经生成')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标|回合|计分')

    await wrapper.findAll('button').find(button => button.text() === '切换到规则答疑')!.trigger('click')
    expect(wrapper.emitted('ask-questions')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps polling when readable content arrives before the persisted run snapshot', async () => {
    let runReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY', sections: [section(1, '目标')] })
      }
      if (path.includes('/assistant-runs/latest')) {
        runReads += 1
        return new Response(null, { status: 404 })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('正在确认后台任务状态')
    expect(runReads).toBe(1)
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(runReads).toBe(2)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标')
    wrapper.unmount()
  })

  it('counts only independently supported chapters as executable progress', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1',
          teachingPlanId: 'plan-1',
          status: 'DRAFT_READY',
          sections: [
            section(1, '已核对', 'SUPPORTED'),
            section(2, '仍在核对', 'CITED_DRAFT'),
            section(3, '证据不足', 'INSUFFICIENT_EVIDENCE'),
          ],
        })
      }
      if (path.includes('/assistant-runs/latest')) return Response.json(runFor('plan-1'))
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('已有 1 / 3 章完成引用、结构与数量校验')
    expect(wrapper.text()).not.toContain('3 / 3')
    expect(wrapper.get('[data-testid="recommendation-lesson-progress"]').attributes('style')).toContain('width: 33%')
    wrapper.unmount()
  })

  it('aborts all initial reads on close and restores from fresh authoritative snapshots on reopen', async () => {
    const closed = [deferred<Response>(), deferred<Response>(), deferred<Response>()]
    const signals: AbortSignal[] = []
    let generation = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      signals.push(options!.signal!)
      if (generation === 0) return closed[signals.length - 1]!.promise
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(plan))
      if (path.includes('/illustrated-lessons/latest')) return Promise.resolve(Response.json(lesson('重新打开')))
      return Promise.resolve(Response.json(runFor('plan-1')))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await wrapper.setProps({ open: false })
    expect(signals).toHaveLength(3)
    expect(signals.every(signal => signal.aborted)).toBe(true)
    generation = 1
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('重新打开')

    closed[0]!.resolve(Response.json(planFor('plan-1', '关闭前')))
    closed[1]!.resolve(Response.json(lesson('关闭前')))
    closed[2]!.resolve(Response.json(runFor('plan-1')))
    await flushPromises()
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('重新打开')
    wrapper.unmount()
  })

  it('cancels an old plan generation and rejects its delayed success and failure', async () => {
    const old = [deferred<Response>(), deferred<Response>(), deferred<Response>()]
    const oldSignals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('plan-old')) {
        oldSignals.push(options!.signal!)
        return old[oldSignals.length - 1]!.promise
      }
      if (path === '/api/v1/teaching-plans/plan-new') return Promise.resolve(Response.json(planFor('plan-new', '新游戏')))
      if (path.includes('plan-new/illustrated-lessons')) return Promise.resolve(Response.json(lesson('新章节', 'plan-new')))
      return Promise.resolve(Response.json(runFor('plan-new')))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-old' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await wrapper.setProps({ planId: 'plan-new' })
    await flushPromises()
    expect(oldSignals).toHaveLength(3)
    expect(oldSignals.every(signal => signal.aborted)).toBe(true)
    expect(wrapper.text()).toContain('新游戏')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('新章节')

    old[0]!.resolve(Response.json(planFor('plan-old', '旧游戏')))
    old[1]!.resolve(new Response(null, { status: 503 }))
    old[2]!.resolve(Response.json(runFor('plan-old')))
    await flushPromises()
    expect(wrapper.text()).toContain('新游戏')
    expect(wrapper.text()).not.toContain('讲解暂时无法打开')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('新章节')
    wrapper.unmount()
  })

  it('aborts an in-flight poll on close and never reschedules after its delayed result', async () => {
    const pendingLesson = deferred<Response>()
    const pendingRun = deferred<Response>()
    const pollSignals: AbortSignal[] = []
    let lessonReads = 0
    let runReads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(plan))
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads === 1) return Promise.resolve(Response.json(lesson()))
        pollSignals.push(options!.signal!)
        return pendingLesson.promise
      }
      runReads += 1
      if (runReads === 1) return Promise.resolve(Response.json(runFor('plan-1')))
      pollSignals.push(options!.signal!)
      return pendingRun.promise
    }))
    const fetchMock = vi.mocked(fetch)
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(pollSignals).toHaveLength(2)

    await wrapper.setProps({ open: false })
    expect(pollSignals.every(signal => signal.aborted)).toBe(true)
    pendingLesson.resolve(Response.json(lesson('迟到章节')))
    pendingRun.resolve(Response.json(runFor('plan-1')))
    await flushPromises()
    const requestsAfterClose = fetchMock.mock.calls.length

    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(requestsAfterClose)
    wrapper.unmount()
  })

  it('aborts a pending poll and clears timers when the guide reader unmounts', async () => {
    const pending = new Promise<Response>(() => undefined)
    const pollSignals: AbortSignal[] = []
    let initialReads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      initialReads += 1
      if (initialReads <= 3) {
        if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(plan))
        if (path.includes('/illustrated-lessons/latest')) return Promise.resolve(Response.json(lesson()))
        return Promise.resolve(Response.json(runFor('plan-1')))
      }
      pollSignals.push(options!.signal!)
      return pending
    }))
    const fetchMock = vi.mocked(fetch)
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(pollSignals).toHaveLength(2)

    wrapper.unmount()
    expect(pollSignals.every(signal => signal.aborted)).toBe(true)
    const requestsAfterUnmount = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(fetchMock).toHaveBeenCalledTimes(requestsAfterUnmount)
  })

  it('keeps readable content and retries polling after an ordinary refresh failure', async () => {
    let lessonReads = 0
    let runReads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(plan))
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        return Promise.resolve(lessonReads === 2
          ? new Response(null, { status: 503 })
          : Response.json(lesson(lessonReads >= 3 ? '恢复章节' : '目标')))
      }
      runReads += 1
      return Promise.resolve(Response.json(runFor('plan-1')))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法刷新最新章节')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('暂时无法刷新最新章节')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('恢复章节')
    expect(runReads).toBe(3)
    wrapper.unmount()
  })

  it('retries a current initial failure with fresh reads and no stale error', async () => {
    const firstSignals: AbortSignal[] = []
    let planReads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        planReads += 1
        if (planReads === 1) {
          firstSignals.push(options!.signal!)
          return Promise.resolve(new Response(null, { status: 503 }))
        }
        return Promise.resolve(Response.json(plan))
      }
      if (planReads === 1) firstSignals.push(options!.signal!)
      if (path.includes('/illustrated-lessons/latest')) return Promise.resolve(Response.json(lesson()))
      return Promise.resolve(Response.json(runFor('plan-1', 'COMPLETED')))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('讲解暂时无法打开')
    expect(firstSignals).toHaveLength(3)
    expect(firstSignals.every(signal => signal.aborted)).toBe(true)
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('讲解暂时无法打开')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标')
    expect(planReads).toBe(2)
    wrapper.unmount()
  })

  it('rejects plan, lesson, or run snapshots that identify another guide', async () => {
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Promise.resolve(Response.json(plan))
      if (path.includes('/illustrated-lessons/latest')) return Promise.resolve(Response.json(lesson('错误章节', 'plan-other')))
      return Promise.resolve(Response.json(runFor('plan-other')))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('讲解暂时无法打开')
    expect(wrapper.find('[data-testid="chapter-list-stub"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('错误章节')
    wrapper.unmount()
  })
})
