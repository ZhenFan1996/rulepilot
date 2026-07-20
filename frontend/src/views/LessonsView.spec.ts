import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LessonsView from './LessonsView.vue'

describe('LessonsView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('shows a persisted active run as safe background work instead of a finished lesson', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-20T10:02:05Z'))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
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
          budget: { usedModelCalls: 1, maxModelCalls: 144 },
          activities: [{
            sequence: 1, type: 'MODEL', operation: 'composeTeachingSection|1', summary: 'Work started',
            outcome: 'RUNNING', latencyMs: 0, occurredAt: '2026-07-20T10:02:00Z',
          }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
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
    expect(wrapper.text()).toContain('正在生成')
    expect(wrapper.text()).toContain('正在阅读规则书图片并编写“完成开局设置”')
    expect(wrapper.text()).toContain('已处理 0/1 节')
    expect(wrapper.text()).toContain('1 次模型调用')
    expect(wrapper.text()).toContain('第一节完成后')
    expect(wrapper.text()).toContain('可以关闭或离开此页')
    expect(wrapper.text()).not.toContain('目录已生成')
    wrapper.unmount()
  })
})
