import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonView from './LessonView.vue'

describe('LessonView progressive reading', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('keeps the reader in place, opens the next published chapter, and unlocks final actions at terminal state', async () => {
    let runReads = 0
    let lessonReads = 0
    let qualityReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans/plan-1') {
        return Response.json({
          id: 'plan-1', documentVersionId: 'version-1', playerCount: 4,
          beginnerCount: 3, durationMinutes: 25, gameTitle: 'SETI', premise: '寻找生命',
        })
      }
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

    expect(wrapper.text()).toContain('正在确认后台生成状态')
    expect(wrapper.text()).toContain('先摆主板')
    expect(wrapper.text()).toContain('这节看懂了，等待下一节')
    expect(wrapper.text()).not.toContain('我学完了')
    expect(qualityReads).toBe(0)

    const waitButton = wrapper.findAll('button')
      .find((button) => button.text().includes('这节看懂了'))
    await waitButton!.trigger('click')
    expect(wrapper.text()).toContain('等待下一节…')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 / 2 节')
    expect(wrapper.text()).toContain('开始第一轮')
    expect(wrapper.text()).toContain('整本仍在后台生成')
    expect(wrapper.text()).toContain('这节看懂了，等待下一节')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.text()).toContain('我学完了')
    expect(wrapper.text()).not.toContain('整本仍在后台生成')
    expect(qualityReads).toBe(1)
    wrapper.unmount()
  })
})

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
    steps: [{
      position: 1, heading: '照着做', kind: 'DO', text: title,
      sourcePages: [position], visualFocus: null,
    }],
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
      { path: '/table/:planId', name: 'table-mode', component: Empty },
      { path: '/account', name: 'account', component: Empty },
      { path: '/settings/models', name: 'model-settings', component: Empty },
      { path: '/login', name: 'login', component: Empty },
    ],
  })
}
