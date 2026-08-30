import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import RecommendationLessonDialog from './RecommendationLessonDialog.vue'

const plan = {
  id: 'plan-1', documentVersionId: 'document-1', gameTitle: '测试讲解', premise: '按规则书学习。',
  unresolvedTopics: ['未发布的局部主题'],
  sections: [
    { position: 1, title: '已发布章', visualEvidenceRecommended: false },
    { position: 2, title: '局部失败章', visualEvidenceRecommended: true },
  ],
}

const lesson = {
  id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY' as const,
  sections: [{
    position: 1, topicKey: 'TOPIC_1', coverageTags: [], title: '已发布章', required: true,
    evidenceStatus: 'SUPPORTED' as const, visualKind: 'FLOW_DIAGRAM', visualCaption: '',
    visualSourcePages: [1], visualSourceChunkIds: ['chunk-1'],
    steps: [{ position: 1, heading: '执行', kind: 'DO', text: '执行规则。', sourcePages: [1], visualFocus: null }],
  }],
}

const ChapterListStub = defineComponent({
  props: { sections: { type: Array, default: () => [] } },
  template: '<div data-testid="chapter-list-stub">{{ sections.map(item => item.title).join("|") }}</div>',
})

describe('RecommendationLessonDialog failure visibility', () => {
  afterEach(() => {
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('keeps published content while a rejected visual is shown as local degradation', async () => {
    mockSnapshots(run('LESSON_COMPOSITION', null, [{
      sequence: 7, type: 'MODEL', operation: 'enrichTeachingSectionVisual|2',
      summary: 'CHAPTER_LOCALLY_UNAVAILABLE', outcome: 'REJECTED', latencyMs: 10,
      occurredAt: '2026-08-30T00:00:01Z',
    }]))
    const wrapper = await mountDialog()

    expect(wrapper.get('[data-testid="chapter-list-stub"]').text()).toContain('已发布章')
    expect(wrapper.text()).toContain('未发布的局部主题')
    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('local-degradation')
    expect(details.text()).toContain('配图处理')
    expect(details.text()).toContain('enrichTeachingSectionVisual|2')
    expect(details.text()).toContain('CHAPTER_LOCALLY_UNAVAILABLE')
    wrapper.unmount()
  })

  it('shows a persistence stop as repair-required with its exact code and preserved chapter count', async () => {
    mockSnapshots(run('FAILED', 'TEACHING_PERSISTENCE_FAILED', []))
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain('已发布的 1 章可读草稿仍然保留')
    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('repair-required')
    expect(details.text()).toContain('讲解保存')
    expect(details.text()).toContain('TEACHING_PERSISTENCE_FAILED')
    wrapper.unmount()
  })

  it('shows an invalid plan as internal correction rather than asking the player to fix input', async () => {
    mockSnapshots(run('FAILED', 'TEACHING_PLAN_INVALID', []))
    const wrapper = await mountDialog()

    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('internal-correction')
    expect(details.text()).toContain('TEACHING_PLAN_INVALID')
    expect(wrapper.get('[data-testid="recommendation-lesson-failure-boundary"]').text())
      .toContain('同一 Agent')
    expect(wrapper.text()).not.toContain('文字处理额度')
    wrapper.unmount()
  })
})

function run(state: string, lastErrorCode: string | null, activities: unknown[]) {
  return {
    run: {
      id: 'run-1', subjectId: 'plan-1', state, createdAt: '2026-08-30T00:00:00Z',
      updatedAt: '2026-08-30T00:00:02Z', completedAt: state === 'FAILED' ? '2026-08-30T00:00:02Z' : null,
      lastErrorCode,
    },
    budget: { usedModelCalls: 1 }, activities,
  }
}

function mockSnapshots(runPayload: unknown) {
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
    const path = String(input)
    if (path === '/api/v1/teaching-plans/plan-1') return Response.json(plan)
    if (path.includes('/illustrated-lessons/latest')) return Response.json(lesson)
    if (path.includes('/assistant-runs/latest')) return Response.json(runPayload)
    return new Response(null, { status: 404 })
  }))
}

async function mountDialog() {
  const wrapper = mount(RecommendationLessonDialog, {
    props: { open: true, planId: 'plan-1' },
    global: { stubs: { LessonChapterList: ChapterListStub, teleport: true } },
  })
  await flushPromises()
  return wrapper
}
