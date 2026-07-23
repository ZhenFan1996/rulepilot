import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { readPendingRulebookLessons, rememberPendingRulebookLesson } from '@/lib/pendingRulebookLesson'

import DocumentsView from './DocumentsView.vue'

describe('DocumentsView recoverable lesson handoff', () => {
  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
    FakeEventSource.instances = []
    vi.unstubAllGlobals()
  })

  it('continues a ready upload after returning with its original preferences', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 3, beginnerCount: 2, durationMinutes: 35,
    })
    const fetchMock = mockApplicationFetch(() => 'READY')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments()
    await flushPromises()
    await flushPromises()

    const planRequest = fetchMock.mock.calls.find(([input]) => String(input).includes('/document-versions/version-1/teaching-plans'))
    expect(planRequest).toBeDefined()
    expect(JSON.parse(String(planRequest![1]?.body))).toEqual({
      playerCount: 3, beginnerCount: 2, durationMinutes: 35,
    })
    expect(router.currentRoute.value.name).toBe('lessons')
    expect(router.currentRoute.value.query.started).toBe('plan-1')
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    wrapper.unmount()
  })

  it('treats a failed terminal progress event as failure and never starts teaching', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 4, beginnerCount: 4, durationMinutes: 25,
    })
    let documentReads = 0
    const fetchMock = mockApplicationFetch(() => (++documentReads === 1 ? 'EXTRACTING' : 'FAILED'))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    expect(FakeEventSource.instances).toHaveLength(1)
    FakeEventSource.instances[0]!.emitProgress({
      stage: 'FAILED', percentage: 100, processedPages: 0, complete: true,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('规则书读取失败，没有启动讲解')
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/teaching-plans'))).toBe(false)
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    wrapper.unmount()
  })

  it('shows an honest resumable stage while visual pages are being organized', async () => {
    vi.useFakeTimers()
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 4, beginnerCount: 4, durationMinutes: 25,
    })
    const fetchMock = mockApplicationFetch(() => 'READY', 'LESSON_PLANNING')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('正在阅读图文并组织讲解顺序')
    expect(wrapper.text()).toContain('已用时 0 秒')
    expect(wrapper.text()).toContain('你可以离开这里，处理会在后台继续')
    expect(wrapper.find('[role="status"]').exists()).toBe(true)
    wrapper.unmount()
    await vi.runOnlyPendingTimersAsync()
  })

  it('shows the real visual-reading batch instead of a silent planning wait', async () => {
    vi.useFakeTimers()
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 4, beginnerCount: 4, durationMinutes: 25,
    })
    const fetchMock = mockApplicationFetch(() => 'READY', 'LESSON_PLANNING', [{
      sequence: 1, operation: 'inspectRulebookVisualBatch|2', outcome: 'RUNNING',
    }])
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('正在识别第 2 组页面里的组件、图标和示例')
    wrapper.unmount()
    await vi.runOnlyPendingTimersAsync()
  })

  it('offers a PDF alongside camera and photo-library rulebook intake', async () => {
    const fetchMock = mockApplicationFetch(() => 'READY')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()

    expect(wrapper.text()).toContain('上传规则书')
    expect(wrapper.text()).toContain('上传 PDF，或按页拍下规则书')
    expect(wrapper.text()).toContain('已有 PDF')
    expect(wrapper.text()).toContain('现在拍一页')
    expect(wrapper.text()).toContain('添加已拍页面')
    expect(wrapper.text()).toContain('想自己起标题？')
    expect(wrapper.text()).toContain('可选：关联游戏、官方链接和讲解偏好')
    expect(wrapper.text()).not.toContain('让 RulePilot 自动创建')
    expect(wrapper.find('#rulebook-file').attributes('accept')).toContain('application/pdf')
    expect(wrapper.find('#rulebook-camera').attributes('capture')).toBe('environment')
    expect(wrapper.find('#rulebook-camera').attributes('accept')).toBe('image/*')
    expect(wrapper.find('#rulebook-gallery').attributes('multiple')).toBeDefined()
    wrapper.unmount()
  })
})

async function mountDocuments() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: DocumentsView },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ],
  })
  await router.push('/teach')
  await router.isReady()
  return { wrapper: mount(DocumentsView, { global: { plugins: [router] } }), router }
}

function mockApplicationFetch(
  documentStatus: () => string,
  preparationState = 'COMPLETED',
  preparationActivities: Array<{ sequence: number; operation: string; outcome: string }> = [],
) {
  return vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
    const path = String(input)
    if (path.includes('/api/auth/session')) return response({ username: 'player' })
    if (path.includes('/api/v1/assistant-runs/active')) return response([])
    if (path.includes('/api/v1/games')) return response([])
    if (path.includes('/api/v1/model-configuration')) {
      return response({
        providers: [{ id: 'qwen', configured: true, visionCapable: true }],
        assignments: { teaching: 'qwen', visual: 'qwen' },
      })
    }
    if (path.endsWith('/api/v1/documents')) {
      return response([{
        document: { id: 'document-1', gameEditionId: null, title: '测试规则书' },
        latestVersion: {
          id: 'version-1', originalFilename: 'rules.pdf', size: 2048, status: documentStatus(),
        },
      }])
    }
    if (path.includes('/api/auth/csrf')) return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
    if (path.includes('/api/v1/assistant-runs/prep-run-1')) {
      return response({
        run: { id: 'prep-run-1', state: preparationState, lastErrorCode: null },
        activities: preparationActivities,
      })
    }
    if (path.endsWith('/document-versions/version-1/teaching-plans/latest')) return response({ id: 'plan-1' })
    if (path.endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST') {
      return response({ assistantRunId: 'prep-run-1', state: 'RECEIVED', reused: false }, 202)
    }
    if (path.includes('/teaching-plans/plan-1/illustrated-lessons')) return response({}, 202)
    return new Response(null, { status: 404 })
  })
}

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onerror: ((event: Event) => void) | null = null
  private progressListener: ((event: MessageEvent<string>) => void) | null = null

  constructor(public readonly url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(name: string, listener: EventListenerOrEventListenerObject) {
    if (name === 'progress') this.progressListener = listener as (event: MessageEvent<string>) => void
  }

  emitProgress(snapshot: unknown) {
    this.progressListener?.(new MessageEvent('progress', { data: JSON.stringify(snapshot) }))
  }

  close() {}
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
