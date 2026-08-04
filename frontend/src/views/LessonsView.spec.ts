import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LessonsView from './LessonsView.vue'

describe('LessonsView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('opens a complete cited draft immediately while detail review continues', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-20T10:02:05Z'))
    let lessonReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([{
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4, beginnerCount: 4,
          durationMinutes: 25, gameTitle: 'SETI', premise: '寻找外星生命。',
          createdAt: '2026-07-20T10:00:00Z', sections: [{ position: 1, required: true, topicKey: 'setup', title: '完成开局设置', visualEvidenceRecommended: true }],
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest')) {
        return Response.json({
          run: { id: 'run-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
          budget: { usedModelCalls: 2, maxModelCalls: 144 },
          activities: [
            {
              sequence: 1, type: 'VALIDATION', operation: 'publishTeachingSection|1',
              summary: 'Teaching section published: CITED_DRAFT_PUBLISHED',
              outcome: 'SUCCEEDED', latencyMs: 0, occurredAt: '2026-07-20T10:01:50Z',
            },
            {
              sequence: 2, type: 'CRITIC', operation: 'reviewPublishedTeachingSection', summary: 'Work started',
              outcome: 'RUNNING', latencyMs: 0, occurredAt: '2026-07-20T10:02:00Z',
            },
          ],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads > 1) return new Response(null, { status: 404 })
        return Response.json({
          id: 'lesson-1', status: 'DRAFT_READY',
          sections: [{ evidenceStatus: 'CITED_DRAFT' }],
        })
      }
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: LessonsView },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
      ],
    })
    await router.push('/lessons?started=plan-1')
    await router.isReady()

    const wrapper = mount(LessonsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('任务已经交给后台')
    expect(wrapper.text()).toContain('可读，核对中')
    expect(wrapper.text()).toContain('基础讲解已可用，正在核对“完成开局设置”的细节')
    expect(wrapper.text()).toContain('已处理 1/1 节')
    expect(wrapper.text()).toContain('2 次模型调用')
    expect(wrapper.text()).toContain('完整基础讲解已经可读')
    expect(wrapper.text()).toContain('立即阅读完整讲解')
    expect(wrapper.text()).not.toContain('目录已生成')
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('可读，核对中')
    expect(wrapper.text()).toContain('立即阅读完整讲解')
    const progressPaths = fetchMock.mock.calls
      .map(([input]) => String(input))
      .filter((path) => path.includes('/api/v1/assistant-runs/latest'))
    expect(progressPaths[1]).toContain('activityRunId=run-1&afterActivitySequence=2')
    expect(wrapper.findAll('[aria-label="最近进度"] li')).toHaveLength(2)
    wrapper.unmount()
  })

  it('starts a fresh polling cycle when a failed run is launched again', async () => {
    vi.useFakeTimers()
    let runReads = 0
    const snapshots = [
      {
        run: { id: 'run-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:00:00Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-1', state: 'FAILED', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:01:00Z', completedAt: '2026-07-20T10:01:00Z', lastErrorCode: 'MODEL_FAILED' },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-2', state: 'RETRIEVING', createdAt: '2026-07-20T10:02:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-2', state: 'RETRIEVING', createdAt: '2026-07-20T10:02:00Z', updatedAt: '2026-07-20T10:02:01Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 2, maxModelCalls: 144 }, activities: [],
      },
    ]
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([{
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4, beginnerCount: 4,
          durationMinutes: 25, gameTitle: 'SETI', premise: '寻找外星生命。', createdAt: '2026-07-20T10:00:00Z',
          sections: [{ position: 1, required: true, topicKey: 'setup', title: '完成开局设置', visualEvidenceRecommended: true }],
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest') || path.includes('/api/v1/assistant-runs/run-2')) {
        return Response.json(snapshots[Math.min(runReads++, snapshots.length - 1)]!)
      }
      if (path.endsWith('/illustrated-lessons') && init?.method === 'POST') {
        return Response.json({ assistantRunId: 'run-2', state: 'RECEIVED', reused: false }, { status: 202 })
      }
      if (path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router] } })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('重新生成')
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(runReads).toBe(2)

    const retry = wrapper.findAll('button').find((button) => button.text().includes('重新生成'))
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('1 次模型调用')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).toContain('2 次模型调用')
    expect(runReads).toBeGreaterThanOrEqual(4)
    wrapper.unmount()
  })

  it('keeps the last progress and automatically retries a transient poll failure', async () => {
    vi.useFakeTimers()
    let runReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([{
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4, beginnerCount: 4,
          durationMinutes: 25, gameTitle: 'SETI', premise: '寻找外星生命。', createdAt: '2026-07-20T10:00:00Z',
          sections: [{ position: 1, required: true, topicKey: 'setup', title: '完成开局设置', visualEvidenceRecommended: true }],
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest')) {
        runReads++
        if (runReads === 2) throw new TypeError('temporary network failure')
        return Response.json({
          run: { id: 'run-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
          budget: { usedModelCalls: runReads >= 3 ? 2 : 1, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router] } })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('1 次模型调用')
    expect(wrapper.text()).toContain('正在自动重试')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('2 次模型调用')
    expect(wrapper.text()).not.toContain('正在自动重试')
    wrapper.unmount()
  })

  it('shows one best continuation per uploaded rulebook until a player asks for history', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([
          {
            id: 'new-plan', documentVersionId: 'version-1', playerCount: 4, beginnerCount: 4,
            durationMinutes: 30, gameTitle: 'Ahoy Rules', premise: '', createdAt: '2026-07-24T10:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
          {
            id: 'readable-plan', documentVersionId: 'version-1', playerCount: 4, beginnerCount: 4,
            durationMinutes: 30, gameTitle: 'Ahoy Rules', premise: '', createdAt: '2026-07-23T10:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
          {
            id: 'pending-plan', documentVersionId: 'version-2', playerCount: 4, beginnerCount: 4,
            durationMinutes: 30, gameTitle: 'Root Rules', premise: '', createdAt: '2026-07-24T11:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
        ])
      }
      if (path.includes('/api/v1/assistant-runs/latest') && path.includes('readable-plan')) {
        return Response.json({
          run: { id: 'run-1', state: 'COMPLETED', createdAt: '2026-07-23T10:00:00Z', updatedAt: '2026-07-23T10:01:00Z', completedAt: '2026-07-23T10:01:00Z', lastErrorCode: null },
          budget: { usedModelCalls: 3, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
      if (path.includes('/illustrated-lessons/latest') && path.includes('readable-plan')) {
        return Response.json({ id: 'lesson-1', status: 'COMPLETE', sections: [{ evidenceStatus: 'SUPPORTED' }] })
      }
      if (path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('共 3 个版本，按 2 本规则书整理；1 本可以继续阅读。')
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Ahoy').length).toBe(1)
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root').length).toBe(0)
    expect(wrapper.text()).toContain('同一本规则书的 1 个历史版本已收起。')

    const pending = wrapper.findAll('button').find((button) => button.text().includes('待处理 1'))
    expect(pending).toBeDefined()
    await pending!.trigger('click')
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root').length).toBe(1)

    const showAll = wrapper.findAll('button').find((button) => button.text().includes('查看全部 3 个版本'))
    expect(showAll).toBeDefined()
    await showAll!.trigger('click')

    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Ahoy').length).toBe(2)
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root').length).toBe(1)
    expect(wrapper.text()).toContain('收起历史版本')
    wrapper.unmount()
  })
})

function createMemoryRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: LessonsView },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ],
  })
}
