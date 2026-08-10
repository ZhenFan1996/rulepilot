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

function section(position: number, title: string) {
  return {
    position, topicKey: `TOPIC_${position}`, coverageTags: [], title, required: true, evidenceStatus: 'SUPPORTED',
    visualKind: 'FLOW_DIAGRAM', visualCaption: '', visualSourcePages: [position], visualSourceChunkIds: [`chunk-${position}`],
    steps: [{ position: 1, heading: title, kind: 'DO', text: `${title}内容`, sourcePages: [position], visualFocus: null }],
  }
}

function run(state: string, updatedAt: string) {
  return {
    run: { id: 'run-1', state, createdAt: '2026-08-10T00:00:00Z', updatedAt, completedAt: state === 'COMPLETED' ? updatedAt : null, lastErrorCode: null },
    budget: { usedModelCalls: 1, maxModelCalls: 10 }, activities: [],
  }
}

const ChapterListStub = defineComponent({
  name: 'LessonChapterList',
  props: { sections: { type: Array, default: () => [] } },
  template: '<div data-testid="chapter-list-stub">{{ sections.map(item => item.title).join("|") }}</div>',
})

describe('RecommendationLessonDialog', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('keeps readable chapters while polling and rejects a stale response with fewer chapters', async () => {
    let lessonRequest = 0
    let runRequest = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
      if (path.includes('/illustrated-lessons/latest')) {
        lessonRequest += 1
        if (lessonRequest === 1) return Response.json({ id: 'lesson-1', status: 'DRAFT_READY', sections: [section(1, '目标')] })
        if (lessonRequest === 2) return Response.json({ id: 'lesson-1', status: 'DRAFT_READY', sections: [section(1, '目标'), section(2, '回合')] })
        if (lessonRequest === 3) return Response.json({ id: 'lesson-1', status: 'DRAFT_READY', sections: [section(1, '目标')] })
        return Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [section(1, '目标'), section(2, '回合'), section(3, '计分')] })
      }
      if (path.includes('/assistant-runs/latest')) {
        runRequest += 1
        return Response.json(run(runRequest >= 4 ? 'COMPLETED' : 'RUNNING', `2026-08-10T00:0${runRequest}:00Z`))
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationLessonDialog, {
      props: { open: true, planId: 'plan-1' },
      global: { stubs: { LessonChapterList: ChapterListStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('已有 1 / 3 章可以阅读')
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
})
