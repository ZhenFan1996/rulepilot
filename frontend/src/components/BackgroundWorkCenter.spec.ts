import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import BackgroundWorkCenter from './BackgroundWorkCenter.vue'

describe('BackgroundWorkCenter visible failure semantics', () => {
  afterEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    setLocale('zh-CN')
    vi.unstubAllGlobals()
  })

  it('shows a rejected chapter visual as local degradation while published siblings remain counted', async () => {
    const activities = [
      activity(1, 'publishTeachingSection|1', 'SUCCEEDED', 'SUPPORTED_SECTION_PUBLISHED'),
      activity(2, 'enrichTeachingSectionVisual|2', 'REJECTED', 'CHAPTER_LOCALLY_UNAVAILABLE'),
    ]
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        return json([{ id: 'run-live', mode: 'TEACHING', subjectId: 'plan-live', ownerUsername: 'player', state: 'LESSON_COMPOSITION' }])
      }
      if (path.endsWith('/api/v1/assistant-runs/run-live')) {
        return json({
          run: {
            id: 'run-live', mode: 'TEACHING', subjectId: 'plan-live', ownerUsername: 'player',
            state: 'LESSON_COMPOSITION', createdAt: '2026-08-30T00:00:00Z', updatedAt: '2026-08-30T00:00:02Z',
            completedAt: null, lastErrorCode: null,
          },
          budget: { usedModelCalls: 2 }, activities,
        })
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        return json([{ id: 'plan-live', gameTitle: '局部失败讲解', sections: [
          { position: 1, title: '已发布章', visualEvidenceRecommended: false },
          { position: 2, title: '配图失败章', visualEvidenceRecommended: true },
        ] }])
      }
      if (isBaseList(path)) return json([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter()
    await openCenter(wrapper)
    const details = wrapper.get('[data-testid="player-failure-details"]')

    expect(wrapper.text()).toContain('已处理 1 / 2 章')
    expect(wrapper.text()).toContain('已发布正文继续保留')
    expect(details.attributes('data-failure-classification')).toBe('local-degradation')
    expect(details.text()).toContain('配图处理')
    expect(details.text()).toContain('enrichTeachingSectionVisual|2')
    expect(details.text()).toContain('CHAPTER_LOCALLY_UNAVAILABLE')
  })

  it('shows a persistence boundary as repair-required with the exact backend code', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return json([])
      if (path.endsWith('/api/v1/documents/official-imports')) return json([{
        id: 'import-failed', title: '保存失败讲解', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-1', errorCode: null,
        teachingHandoffState: 'FAILED', teachingPreparationRunId: null,
        teachingErrorCode: 'TEACHING_PREPARATION_STORAGE_FAILED', teachingNextAction: 'NONE',
        updatedAt: '2026-08-30T00:00:00Z',
      }])
      if (path.endsWith('/api/v1/documents')) return json([{
        document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return json([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter()
    await openCenter(wrapper)
    const details = wrapper.get('[data-testid="player-failure-details"]')

    expect(details.attributes('data-failure-classification')).toBe('repair-required')
    expect(details.text()).toContain('讲解保存')
    expect(details.text()).toContain('TEACHING_PREPARATION_STORAGE_FAILED')
  })

  it('does not promise fixed queue durations', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return json([])
      if (path.endsWith('/api/v1/documents/official-imports')) return json([{
        id: 'import-queued', title: '排队中的讲解', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-1', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-1', teachingErrorCode: null,
        updatedAt: '2026-08-30T00:00:00Z',
      }])
      if (path.endsWith('/api/v1/documents')) return json([{
        document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return json([])
      if (path.endsWith('/api/v1/assistant-runs/prep-1')) return json({
        run: {
          id: 'prep-1', mode: 'TEACHING_PREPARATION', subjectId: 'version-1', ownerUsername: 'player',
          state: 'RECEIVED', createdAt: '2026-08-30T00:00:00Z', updatedAt: '2026-08-30T00:00:00Z',
          completedAt: null, lastErrorCode: null,
        },
        budget: { usedModelCalls: 0 }, activities: [],
      })
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('排队状态和停止原因以后端实时记录为准')
    expect(wrapper.text()).not.toMatch(/2\s*分钟|30\s*分钟/)
  })
})

async function mountCenter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(BackgroundWorkCenter, { props: { username: 'player' }, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

async function openCenter(wrapper: ReturnType<typeof mount>) {
  ;(wrapper.vm as unknown as { openCenter: () => void }).openCenter()
  await wrapper.vm.$nextTick()
}

function activity(sequence: number, operation: string, outcome: string, summary: string) {
  return {
    sequence, type: operation.startsWith('publish') ? 'VALIDATION' : 'MODEL', operation, summary, outcome,
    latencyMs: 10, occurredAt: `2026-08-30T00:00:0${sequence}Z`,
  }
}

function isBaseList(path: string) {
  return path.endsWith('/api/v1/documents/official-imports')
    || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
    || path.endsWith('/api/v1/documents')
}

function json(body: unknown) {
  return Response.json(body)
}
