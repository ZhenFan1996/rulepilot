import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'
import { VISUAL_REQUEST_TIMEOUT_MS } from '@/lib/visualEnrichment'

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
type TestVisualFocus = {
  pageNumber: number
  label: string
  x: number
  y: number
  width: number
  height: number
}

function section(position: number, title: string, evidenceStatus: EvidenceStatus = 'SUPPORTED') {
  return {
    position, topicKey: `TOPIC_${position}`, coverageTags: [], title, required: true, evidenceStatus,
    visualKind: 'FLOW_DIAGRAM', visualCaption: '', visualSourcePages: [position], visualSourceChunkIds: [`chunk-${position}`],
    steps: [{
      position: 1,
      heading: title,
      kind: 'DO',
      text: `${title}内容`,
      sourcePages: [position],
      visualFocus: null as TestVisualFocus | null,
    }],
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
  template: '<div data-testid="chapter-list-stub">{{ sections.map(item => item.title).join("|") }}<span v-for="item in sections" :key="item.position">{{ item.steps.map(step => step.visualFocus?.label ?? "").join("") }}</span></div>',
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
    expect(wrapper.text()).toContain('已有 1 / 3 章完成引用归属、规则书版本与结构校验')
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

  it('reconciles a readable seed after the run completes but the persisted lesson is still a draft', async () => {
    let lessonReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        return Response.json(lessonReads === 1
          ? {
              id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
              sections: [section(1, '目标'), section(2, '回合')],
            }
          : {
              id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
              sections: [section(1, '目标'), section(2, '回合'), section(3, '计分')],
            })
      }
      if (path.includes('/assistant-runs/latest')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:03:00Z'))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: {
        open: true,
        planId: 'plan-1',
        initialPlan: plan,
        initialLesson: {
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [section(1, '目标'), section(2, '回合')],
        },
      },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标|回合')
    expect(wrapper.text()).not.toContain('完整讲解已经生成')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(lessonReads).toBe(2)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标|回合|计分')
    expect(wrapper.text()).toContain('完整讲解已经生成')
    wrapper.unmount()
  })

  it('keeps tracking visual enrichment after the text lesson completes and paints a late focused crop', async () => {
    let visualRunReads = 0
    let lessonReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: plan.sections.map((item, index) => ({
            ...item,
            visualEvidenceRecommended: index === 0,
          })),
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        const first = section(1, '目标')
        if (lessonReads >= 4) {
          first.steps[0]!.visualFocus = {
            pageNumber: 1,
            label: '主棋盘区域',
            x: 100,
            y: 200,
            width: 500,
            height: 400,
          }
        }
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [first],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        const snapshot = run(
          visualRunReads === 1 ? 'RETRIEVING' : 'COMPLETED',
          `2026-08-10T00:0${visualRunReads}:00Z`,
        )
        return Response.json({
          ...snapshot,
          activities: visualRunReads === 1 ? [] : [{
            sequence: 1,
            type: 'VALIDATION',
            operation: 'visualSection|1',
            summary: 'focused crop published',
            outcome: 'SUCCEEDED',
            latencyMs: 20,
            occurredAt: '2026-08-10T00:02:00Z',
          }],
        })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('目标')
    await vi.advanceTimersByTimeAsync(249)
    await flushPromises()
    expect(visualRunReads).toBe(0)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(visualRunReads).toBe(1)

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(visualRunReads).toBe(2)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).not.toContain('主棋盘区域')
    expect(wrapper.text()).toContain('已有 1 节具备图示')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('主棋盘区域')
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(visualRunReads).toBe(2)
    wrapper.unmount()
  })

  it('spends terminal visual settling reads only on accepted lesson snapshots and recovers a late crop', async () => {
    let visualRunReads = 0
    let lessonReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads === 4) return new Response(null, { status: 404 })
        if (lessonReads === 5) return new Response(null, { status: 503 })
        if (lessonReads === 6) {
          return new Response('{', { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        const first = section(1, '目标')
        if (lessonReads >= 7) {
          first.steps[0]!.visualFocus = {
            pageNumber: 1,
            label: '失败恢复后的主棋盘',
            x: 100,
            y: 200,
            width: 500,
            height: 400,
          }
        }
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [first],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        const snapshot = run(
          visualRunReads === 1 ? 'RETRIEVING' : 'COMPLETED',
          `2026-08-10T00:0${visualRunReads}:00Z`,
        )
        return Response.json({ ...snapshot, run: { ...snapshot.run, id: 'visual-run' } })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    for (let refresh = 0; refresh < 6; refresh += 1) {
      await vi.runOnlyPendingTimersAsync()
      await flushPromises()
    }

    expect(visualRunReads).toBe(2)
    expect(lessonReads).toBe(7)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('失败恢复后的主棋盘')
    expect(wrapper.text()).not.toContain('暂时无法刷新最新章节')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(lessonReads).toBe(8)
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(lessonReads).toBe(8)
    wrapper.unmount()
  })

  it.each([
    ['missing', () => new Response(null, { status: 404 })],
    ['unavailable', () => new Response(null, { status: 503 })],
    ['invalid', () => new Response('{', { status: 200, headers: { 'Content-Type': 'application/json' } })],
  ])('stops repeated %s settling snapshots and resumes late crop publication only after retry', async (_kind, failedResponse) => {
    let visualRunReads = 0
    let lessonReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads >= 4 && !recovered) return failedResponse()
        const first = section(1, '目标')
        if (recovered) {
          first.steps[0]!.visualFocus = {
            pageNumber: 1,
            label: '手动恢复后的主棋盘',
            x: 100,
            y: 200,
            width: 500,
            height: 400,
          }
        }
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [first],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        const snapshot = run(
          visualRunReads === 1 ? 'RETRIEVING' : 'COMPLETED',
          `2026-08-10T00:0${visualRunReads}:00Z`,
        )
        return Response.json({ ...snapshot, run: { ...snapshot.run, id: 'visual-run' } })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    for (let failure = 0; failure < 4; failure += 1) {
      await vi.runOnlyPendingTimersAsync()
      await flushPromises()
    }

    expect(lessonReads).toBe(7)
    expect(wrapper.get('[data-testid="recommendation-lesson-visual-status"]').text())
      .toContain('暂时无法确认最新配图状态')
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(lessonReads).toBe(7)

    recovered = true
    await wrapper.get('[data-testid="recommendation-lesson-visual-status"] button').trigger('click')
    await vi.runOnlyPendingTimersAsync()
    await flushPromises()
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('手动恢复后的主棋盘')

    await vi.runOnlyPendingTimersAsync()
    await flushPromises()
    expect(lessonReads).toBe(9)
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('stops missing visual discovery visibly and recovers a late completed run after retry', async () => {
    let visualRunReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: plan.sections.map((item, index) => ({
            ...item,
            visualEvidenceRecommended: index === 0,
          })),
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [section(1, '文字讲解已完成')],
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        if (!recovered) return new Response(null, { status: 404 })
        const lateRun = run('COMPLETED', '2026-08-10T00:04:00Z')
        return Response.json({
          ...lateRun,
          run: { ...lateRun.run, id: 'late-visual-run' },
          activities: [{
            sequence: 1,
            type: 'VALIDATION',
            operation: 'visualSection|1',
            summary: 'late visual published',
            outcome: 'SUCCEEDED',
            latencyMs: 1,
            occurredAt: '2026-08-10T00:04:00Z',
          }],
        })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    expect(wrapper.find('[data-testid="recommendation-lesson-visual-status"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(visualRunReads).toBe(2)
    expect(vi.getTimerCount()).toBe(0)
    expect(wrapper.get('[data-testid="recommendation-lesson-visual-status"]').text())
      .toContain('暂时无法确认最新配图状态')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('文字讲解已完成')

    recovered = true
    await wrapper.get('[data-testid="recommendation-lesson-visual-status"] button').trigger('click')
    await vi.advanceTimersByTimeAsync(249)
    await flushPromises()
    expect(visualRunReads).toBe(2)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(wrapper.get('[data-testid="recommendation-lesson-visual-status"]').text())
      .toContain('已有 1 节具备图示')
    wrapper.unmount()
  })

  it('waits for the current teaching run to become explicitly terminal before discovering visuals', async () => {
    let teachingRunReads = 0
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: plan.sections.map((item, index) => ({
            ...item,
            visualEvidenceRecommended: index === 0,
          })),
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [section(1, '文字先到')],
        })
      }
      if (path.includes('mode=TEACHING')) {
        teachingRunReads += 1
        return teachingRunReads === 1
          ? new Response(null, { status: 404 })
          : Response.json(run('COMPLETED', '2026-08-10T00:02:00Z'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json({
          ...run('COMPLETED', '2026-08-10T00:03:00Z'),
          run: {
            ...run('COMPLETED', '2026-08-10T00:03:00Z').run,
            id: 'visual-run-1',
          },
          activities: [{
            sequence: 1, type: 'VALIDATION', operation: 'visualSection|1', summary: 'opaque',
            outcome: 'SUCCEEDED', latencyMs: 1, occurredAt: '2026-08-10T00:03:00Z',
          }],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(teachingRunReads).toBe(1)
    expect(visualRunReads).toBe(0)
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(teachingRunReads).toBe(2)
    expect(visualRunReads).toBe(0)

    await vi.advanceTimersByTimeAsync(1_499)
    await flushPromises()
    expect(visualRunReads).toBe(0)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    expect(wrapper.text()).toContain('已有 1 节具备图示')
    wrapper.unmount()
  })

  it('settles a cancelled visual run as unfinished without continuing visual polling', async () => {
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '文字讲解已保留')],
        })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:02:00Z'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return Response.json({
          ...runFor('plan-1', 'CANCELLED'),
          run: { ...runFor('plan-1', 'CANCELLED').run, id: 'visual-run-1' },
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(visualRunReads).toBe(1)
    const status = wrapper.get('[data-testid="recommendation-lesson-visual-status"]')
    expect(status.text()).toContain('局部配图没有完成')
    expect(status.text()).not.toContain('正在从规则书中挑选')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    wrapper.unmount()
  })

  it('drops a visual discovery response when a newer teaching run becomes current and resets the new run budget', async () => {
    let teachingRunReads = 0
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '当前文字')],
        })
      }
      if (path.includes('mode=TEACHING')) {
        teachingRunReads += 1
        const current = run(
          teachingRunReads === 2 ? 'RETRIEVING' : 'COMPLETED',
          `2026-08-10T00:0${teachingRunReads}:00Z`,
        )
        return Response.json({
          ...current,
          run: {
            ...current.run,
            id: teachingRunReads === 1 ? 'teaching-run-1' : 'teaching-run-2',
          },
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        const candidate = run('COMPLETED', `2026-08-10T00:1${visualRunReads}:00Z`)
        return Response.json({
          ...candidate,
          run: { ...candidate.run, id: `visual-run-${visualRunReads}` },
          activities: [{
            sequence: 1,
            type: 'VALIDATION',
            operation: `visualSection|${visualRunReads}`,
            summary: 'opaque',
            outcome: 'SUCCEEDED',
            latencyMs: 1,
            occurredAt: '2026-08-10T00:10:00Z',
          }],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    expect(wrapper.text()).not.toContain('已有 1 节具备图示')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(teachingRunReads).toBe(3)
    expect(visualRunReads).toBe(1)
    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(visualRunReads).toBe(2)
    expect(wrapper.text()).toContain('已有 1 节具备图示')
    wrapper.unmount()
  })

  it('keeps 503 and network failures separate from absence and recovers within a finite budget', async () => {
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [section(1, '文字讲解')],
        })
      }
      if (path.includes('mode=TEACHING')) return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        if (visualRunReads === 1) return new Response(null, { status: 503 })
        if (visualRunReads === 2) throw new TypeError('network unavailable')
        return Response.json({
          ...run('COMPLETED', '2026-08-10T00:04:00Z'),
          run: { ...run('COMPLETED', '2026-08-10T00:04:00Z').run, id: 'visual-run' },
          activities: [{
            sequence: 1, type: 'VALIDATION', operation: 'visualSection|1', summary: 'opaque',
            outcome: 'SUCCEEDED', latencyMs: 1, occurredAt: '2026-08-10T00:04:00Z',
          }],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    await vi.advanceTimersByTimeAsync(3_000)
    await flushPromises()
    expect(visualRunReads).toBe(2)
    await vi.advanceTimersByTimeAsync(6_000)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(wrapper.text()).toContain('已有 1 节具备图示')
    wrapper.unmount()
  })

  it('publishes a late core snapshot while a visual request is pending and enforces its own deadline', async () => {
    let lessonReads = 0
    let visualRunReads = 0
    let visualSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Promise.resolve(Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        }))
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        return Promise.resolve(Response.json({
          id: 'lesson-1',
          teachingPlanId: 'plan-1',
          status: lessonReads >= 2 ? 'COMPLETE' : 'DRAFT_READY',
          sections: lessonReads >= 2
            ? [section(1, '目标'), section(2, '视觉请求未阻塞的迟到章节')]
            : [section(1, '目标')],
        }))
      }
      if (path.includes('mode=TEACHING')) {
        return Promise.resolve(Response.json(run('COMPLETED', '2026-08-10T00:01:00Z')))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        visualSignal = options?.signal ?? undefined
        return new Promise<Response>(() => undefined)
      }
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(visualRunReads).toBe(1)
    expect(visualSignal?.aborted).toBe(false)
    expect(lessonReads).toBe(2)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text())
      .toContain('视觉请求未阻塞的迟到章节')
    expect(wrapper.text()).toContain('完整讲解已经生成')

    await vi.advanceTimersByTimeAsync(VISUAL_REQUEST_TIMEOUT_MS - 1)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    expect(visualSignal?.aborted).toBe(false)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text())
      .toContain('视觉请求未阻塞的迟到章节')

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(visualSignal?.aborted).toBe(true)
    expect(visualRunReads).toBe(1)

    await vi.advanceTimersByTimeAsync(2_999)
    await flushPromises()
    expect(visualRunReads).toBe(1)
    wrapper.unmount()
  })

  it('finishes the text lesson snapshot after visual refresh failures exhaust their own budget', async () => {
    let lessonReads = 0
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        return Response.json({
          id: 'lesson-1',
          teachingPlanId: 'plan-1',
          status: lessonReads >= 5 ? 'COMPLETE' : 'DRAFT_READY',
          sections: lessonReads >= 5
            ? [section(1, '目标'), section(2, '最终章节')]
            : [section(1, '目标')],
        })
      }
      if (path.includes('mode=TEACHING')) {
        return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return new Response(null, { status: 503 })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250 + 1_500 + 1_500)
    await flushPromises()
    expect(visualRunReads).toBe(3)
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')
    expect(wrapper.text()).not.toContain('完整讲解已经生成')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(visualRunReads).toBe(3)
    expect(lessonReads).toBe(5)
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('最终章节')
    expect(wrapper.text()).toContain('完整讲解已经生成')
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('stops stale active visual polling after bounded transport failures and permits an explicit retry', async () => {
    let visualRunReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '可读文字')],
        })
      }
      if (path.includes('mode=TEACHING')) return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        if (visualRunReads === 1) {
          return Response.json({
            ...run('RETRIEVING', '2026-08-10T00:02:00Z'),
            run: { ...run('RETRIEVING', '2026-08-10T00:02:00Z').run, id: 'visual-run' },
          })
        }
        if (!recovered) return new Response(null, { status: 503 })
        return Response.json({
          ...run('COMPLETED', '2026-08-10T00:05:00Z'),
          run: { ...run('COMPLETED', '2026-08-10T00:05:00Z').run, id: 'visual-run' },
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(250 + 1_500 + 3_000 + 6_000)
    await flushPromises()
    expect(visualRunReads).toBe(4)
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(visualRunReads).toBe(4)

    recovered = true
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualRunReads).toBe(5)
    expect(wrapper.text()).toContain('这次没有找到可靠的局部图示')
    wrapper.unmount()
  })

  it('keeps a visual 401 at the authentication boundary and never treats it as absence', async () => {
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({ ...plan, sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }] })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '可读文字')] })
      }
      if (path.includes('mode=TEACHING')) return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        return new Response(null, { status: 401 })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(loginRequired).toHaveBeenCalledOnce()
    expect(visualRunReads).toBe(1)
    expect(wrapper.text()).toContain('暂时无法确认最新配图状态')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(visualRunReads).toBe(1)
    window.removeEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    wrapper.unmount()
  })

  it.each([
    ['missing', undefined],
    ['cross-plan', 'plan-other'],
  ])('rejects a %s visual subject identity without publishing its status', async (_label, subjectId) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({ ...plan, sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }] })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '可信文字')] })
      }
      if (path.includes('mode=TEACHING')) return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        const candidate = run('COMPLETED', '2026-08-10T00:02:00Z')
        return Response.json({
          ...candidate,
          run: { ...candidate.run, id: 'visual-run', subjectId },
          activities: [{
            sequence: 1, type: 'VALIDATION', operation: 'visualSection|1', summary: 'opaque',
            outcome: 'SUCCEEDED', latencyMs: 1, occurredAt: '2026-08-10T00:02:00Z',
          }],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('可信文字')
    expect(wrapper.text()).not.toContain('已有 1 节具备图示')
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('resets visual discovery for a newly selected plan after the previous plan exhausted its budget', async () => {
    const visualReads = new Map<string, number>()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      const targetPlanId = path.includes('plan-2') ? 'plan-2' : 'plan-1'
      if (path === `/api/v1/teaching-plans/${targetPlanId}`) {
        return Response.json({
          ...planFor(targetPlanId, targetPlanId),
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: `lesson-${targetPlanId}`, teachingPlanId: targetPlanId, status: 'COMPLETE',
          sections: [section(1, targetPlanId)],
        })
      }
      if (path.includes('mode=TEACHING')) return Response.json(runFor(targetPlanId, 'COMPLETED'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualReads.set(targetPlanId, (visualReads.get(targetPlanId) ?? 0) + 1)
        return new Response(null, { status: 404 })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250 + 1_500)
    await flushPromises()
    expect(visualReads.get('plan-1')).toBe(2)

    await wrapper.setProps({ planId: 'plan-2' })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(visualReads.get('plan-2')).toBe(1)
    expect(wrapper.text()).toContain('plan-2')
    wrapper.unmount()
  })

  it('renders visual lifecycle notices outside the sticky header in a narrow-width-safe structure', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({ ...plan, sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }] })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '移动端')] })
      }
      if (path.includes('mode=TEACHING')) return Response.json(run('COMPLETED', '2026-08-10T00:01:00Z'))
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        return Response.json({ ...run('RETRIEVING', '2026-08-10T00:02:00Z'), run: { ...run('RETRIEVING', '2026-08-10T00:02:00Z').run, id: 'visual-run' } })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()

    const header = wrapper.get('header')
    const notices = wrapper.get('[data-testid="recommendation-lesson-runtime-notices"]')
    expect(header.classes()).toContain('sticky')
    expect(header.find('[data-testid="recommendation-lesson-visual-status"]').exists()).toBe(false)
    expect(notices.element.previousElementSibling).toBe(header.element)
    expect(notices.get('[data-testid="recommendation-lesson-visual-status"]').classes()).toContain('break-words')
    wrapper.unmount()
  })

  it('stops missing teaching-run reads visibly and converges on a late terminal run after retry', async () => {
    let runReads = 0
    let recovered = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE', sections: [section(1, '目标')] })
      }
      if (path.includes('/assistant-runs/latest')) {
        runReads += 1
        return recovered
          ? Response.json(run('COMPLETED', '2026-08-10T00:05:00Z'))
          : new Response(null, { status: 404 })
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
    expect(wrapper.text()).not.toContain('暂时无法刷新最新章节')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(runReads).toBe(3)
    expect(wrapper.text()).toContain('暂时无法刷新最新章节')
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(runReads).toBe(3)

    recovered = true
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await vi.advanceTimersByTimeAsync(249)
    await flushPromises()
    expect(runReads).toBe(3)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    expect(runReads).toBe(4)
    expect(wrapper.text()).toContain('完整讲解已经生成')
    expect(wrapper.text()).not.toContain('正在确认后台任务状态')
    expect(wrapper.text()).not.toContain('暂时无法刷新最新章节')
    expect(vi.getTimerCount()).toBe(0)
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

    expect(wrapper.text()).toContain('已有 1 / 3 章完成引用归属、规则书版本与结构校验')
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

  it('clears the previous visual run when reopening the same plan for a new teaching run', async () => {
    let generation = 1
    let visualRunReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          ...plan,
          sections: [{ ...plan.sections[0]!, visualEvidenceRecommended: true }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: `lesson-${generation}`,
          teachingPlanId: 'plan-1',
          status: 'COMPLETE',
          sections: [section(1, `正文-${generation}`)],
        })
      }
      if (path.includes('mode=TEACHING')) {
        const snapshot = run('COMPLETED', `2026-08-10T00:0${generation}:00Z`)
        return Response.json({
          ...snapshot,
          run: { ...snapshot.run, id: `teaching-${generation}` },
        })
      }
      if (path.includes('mode=VISUAL_ENRICHMENT')) {
        visualRunReads += 1
        if (generation === 2) return new Response(null, { status: 404 })
        const snapshot = run('COMPLETED', '2026-08-10T00:03:00Z')
        return Response.json({
          ...snapshot,
          run: { ...snapshot.run, id: 'visual-1' },
          activities: [{
            sequence: 1,
            type: 'VALIDATION',
            operation: 'visualSection|1',
            summary: 'opaque',
            outcome: 'SUCCEEDED',
            latencyMs: 1,
            occurredAt: '2026-08-10T00:03:00Z',
          }],
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(250)
    await flushPromises()
    expect(wrapper.text()).toContain('已有 1 节具备图示')

    await wrapper.setProps({ open: false })
    generation = 2
    await wrapper.setProps({ open: true })
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('正文-2')
    expect(wrapper.find('[data-testid="recommendation-lesson-visual-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('已有 1 节具备图示')
    expect(visualRunReads).toBe(1)
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
