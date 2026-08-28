import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'

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
  beforeEach(() => {
    setLocale('zh-CN')
    vi.useFakeTimers()
  })
  afterEach(() => {
    setLocale('zh-CN')
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

  it('names an expired session, preserves the readable seed, and stops automatic refresh', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationLessonDialog, {
      props: {
        open: true,
        planId: 'plan-1',
        initialPlan: plan,
        initialLesson: lesson('登录前已发布章节'),
      },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('登录会话已失效，已停止刷新')
    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toBe('登录前已发布章节')
    const requestsAtStop = fetchMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(requestsAtStop)
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
    expect(wrapper.get('[data-testid="recommendation-lesson-failure-boundary"]').text())
      .toContain('一次返回被拒或一次服务调用失败')
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

  it('publishes synchronized chapter visuals from the main teaching snapshots without a visual-enrichment run', async () => {
    let lessonReads = 0
    let runReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        const published = section(1, '目标')
        if (lessonReads > 1) {
          published.steps[0]!.visualFocus = {
            pageNumber: 1,
            label: '同步局部图',
            x: 100,
            y: 200,
            width: 300,
            height: 400,
          }
        }
        return Response.json({
          id: 'lesson-1',
          teachingPlanId: 'plan-1',
          status: lessonReads === 1 ? 'DRAFT_READY' : 'COMPLETE',
          sections: [published],
        })
      }
      if (path.includes('mode=TEACHING')) {
        runReads += 1
        return Response.json(run(runReads === 1 ? 'RETRIEVING' : 'COMPLETED', '2026-08-10T00:03:00Z'))
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).not.toContain('同步局部图')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('同步局部图')
    expect(fetchMock.mock.calls.map(([input]) => String(input)).some(path => path.includes('mode=VISUAL_ENRICHMENT'))).toBe(false)
    expect(runReads).toBe(2)
    expect(lessonReads).toBe(2)
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
    expect(wrapper.text()).not.toContain('有限刷新重试已用完')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()

    expect(runReads).toBe(3)
    expect(wrapper.text()).toContain('有限刷新重试已用完，已停止自动刷新')
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
    expect(wrapper.text()).not.toContain('有限刷新重试已用完')
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
    const progressbar = wrapper.get('[role="progressbar"]')
    expect(progressbar.get('[data-testid="recommendation-lesson-progress"]').attributes('style')).toContain('width: 33%')
    expect(progressbar.attributes('aria-valuenow')).toBe('1')
    expect(progressbar.attributes('aria-valuemax')).toBe('3')
    expect(progressbar.attributes('aria-label')).toBe('1 / 3 章已通过独立规则依据核对')
    const citedDraftStatus = wrapper.get('[data-testid="recommendation-lesson-cited-draft-status"]')
    expect(citedDraftStatus.text()).toContain('另有 1 章已通过确定性引用与结构校验，现在可以阅读')
    expect(citedDraftStatus.text()).not.toContain('这不是生成失败')
    const liveStatus = wrapper.get('[data-testid="recommendation-lesson-teaching-status"]')
    expect(liveStatus.attributes('role')).toBe('status')
    expect(liveStatus.attributes('aria-live')).toBe('polite')
    expect(liveStatus.attributes('aria-atomic')).toBe('true')
    expect(liveStatus.text()).toContain('额外内容复核尚未完成')
    wrapper.unmount()
  })

  it.each([
    ['zh-CN', 'FAILED', null, '本轮讲解生成失败', '失败'],
    ['zh-CN', 'FAILED', 'AGENT_CANCELLED', '本轮讲解生成已取消', '已取消'],
    ['en', 'FAILED', null, 'This guide generation run failed', 'failed'],
    ['en', 'FAILED', 'AGENT_CANCELLED', 'This guide generation run was cancelled', 'cancelled'],
  ] as const)('prioritizes an authoritative %s %s outcome over a retained cited draft', async (
    appLocale,
    state,
    lastErrorCode,
    expectedStatus,
    expectedOutcomeWord,
  ) => {
    setLocale(appLocale)
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [section(1, '可读草稿', 'CITED_DRAFT')],
        })
      }
      if (path.includes('/assistant-runs/latest')) {
        const snapshot = runFor('plan-1', state)
        return Response.json({
          ...snapshot,
          run: { ...snapshot.run, lastErrorCode },
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    const statusText = wrapper.get('[data-testid="recommendation-lesson-teaching-status-text"]')
    expect(statusText.text()).toContain(expectedStatus)
    expect(statusText.text().toLocaleLowerCase()).toContain(expectedOutcomeWord.toLocaleLowerCase())
    expect(statusText.classes()).toContain('text-amber-800')
    expect(statusText.classes()).not.toContain('text-emerald-700')
    const citedDraftStatus = wrapper.get('[data-testid="recommendation-lesson-cited-draft-status"]')
    expect(citedDraftStatus.text()).not.toContain('这不是生成失败')
    expect(citedDraftStatus.text()).not.toContain('not a generation failure')
    expect(wrapper.get('[data-testid="recommendation-lesson-teaching-status"]').text())
      .toContain(appLocale === 'en' ? 'readable chapter draft' : '可读草稿')
    expect(wrapper.get('[data-testid="recommendation-lesson-failure-boundary"]').text())
      .toContain(appLocale === 'en'
        ? 'One rejected response or service call starts a bounded correction or retry'
        : '一次返回被拒或一次服务调用失败')
    wrapper.unmount()
  })

  it('does not call a completed lesson fully ready when any chapter remains a cited draft', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'COMPLETE',
          sections: [
            section(1, '已核对', 'SUPPORTED'),
            section(2, '引用草稿', 'CITED_DRAFT'),
            section(3, '已核对', 'SUPPORTED'),
          ],
        })
      }
      if (path.includes('/assistant-runs/latest')) return Response.json(runFor('plan-1', 'COMPLETED'))
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('完整讲解已经生成')
    expect(wrapper.get('[data-testid="recommendation-lesson-teaching-status-text"]').text())
      .toContain('可读讲解草稿')
    expect(wrapper.get('[data-testid="recommendation-lesson-teaching-status-text"]').classes())
      .toContain('text-amber-800')
    wrapper.unmount()
  })

  it.each(['DEGRADED', 'INSUFFICIENT_EVIDENCE'])('keeps a readable subset local when the authoritative run ends as %s', async (state) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'INCOMPLETE',
          sections: [
            section(1, '可读章节', 'SUPPORTED'),
            section(2, '证据不足', 'INSUFFICIENT_EVIDENCE'),
          ],
        })
      }
      if (path.includes('/assistant-runs/latest')) return Response.json(runFor('plan-1', state))
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
    })
    await flushPromises()

    const statusText = wrapper.get('[data-testid="recommendation-lesson-teaching-status-text"]')
    expect(statusText.text()).toContain('已保留 1 章可读内容')
    expect(statusText.text()).toContain('证据不足的章节未作为完整规则讲解发布')
    expect(statusText.classes()).toContain('text-amber-800')
    expect(statusText.classes()).not.toContain('text-emerald-700')
    expect(wrapper.text()).not.toContain('完整讲解已经生成')
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
