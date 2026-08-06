import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import { readPendingRulebookLessons, rememberPendingRulebookLesson } from '@/lib/pendingRulebookLesson'

import DocumentsView from './DocumentsView.vue'

describe('DocumentsView recoverable lesson handoff', () => {
  afterEach(() => {
    setLocale('zh-CN')
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
    expect(router.currentRoute.value.query.run).toBe('lesson-run-1')
    expect(fetchMock.mock.calls.some(([input, options]) =>
      String(input).endsWith('/teaching-plans/plan-1/illustrated-lessons') && options?.method === 'POST')).toBe(true)
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

  it('shows rendered page position instead of a generic reading wait', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 4, beginnerCount: 4, durationMinutes: 25,
    })
    vi.stubGlobal('fetch', mockApplicationFetch(() => 'EXTRACTING'))
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()

    FakeEventSource.instances[0]!.emitProgress({
      stage: 'RENDERING', percentage: 52, processedPages: 14, totalPages: 28, complete: false,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('正在生成图解页面：第 14 / 28 页')
    wrapper.unmount()
  })

  it('names the structural pass that follows visual page rendering', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1', playerCount: 4, beginnerCount: 4, durationMinutes: 25,
    })
    vi.stubGlobal('fetch', mockApplicationFetch(() => 'EXTRACTING'))
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()

    FakeEventSource.instances[0]!.emitProgress({
      stage: 'STRUCTURING', percentage: 75, processedPages: 28, totalPages: 28, complete: false,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('正在整理章节、规则和图例索引…')
    FakeEventSource.instances[0]!.emitProgress({
      stage: 'RENDERING', percentage: 52, processedPages: 14, totalPages: 28, complete: false,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('正在整理章节、规则和图例索引…')
    expect(wrapper.text()).not.toContain('第 14 / 28 页')
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
    expect(wrapper.get('label[for="rulebook-camera"]').classes()).toContain('bg-paper')
    expect(wrapper.get('label[for="rulebook-gallery"]').classes()).toContain('bg-paper')
    expect(wrapper.get('label[for="rulebook-camera"]').classes()).not.toContain('bg-[#fffaf2]')

    setLocale('en')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Photograph a page')
    expect(wrapper.text()).toContain('Add photographed pages')
    expect(wrapper.text()).not.toContain('现在拍一页')
    wrapper.unmount()
  })

  it('requires a direct URL and explicit rights confirmation before importing an official PDF', async () => {
    let importOptions: RequestInit | undefined
    const fetchMock = mockApplicationFetch(
      () => 'READY',
      'COMPLETED',
      [],
      undefined,
      undefined,
      (options) => {
        importOptions = options
        return response({ duplicate: false, version: { id: 'imported-version', status: 'EXTRACTING' } }, 201)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    const importButton = wrapper.findAll('button').find((button) => button.text() === '下载并生成讲解')!
    expect(importButton.attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="url"]').setValue('https://publisher.example/wingspan_rules.pdf')
    expect(importButton.attributes('disabled')).toBeDefined()
    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(importButton.attributes('disabled')).toBeUndefined()

    await importButton.trigger('click')
    await flushPromises()

    expect(JSON.parse(String(importOptions?.body))).toEqual({
      editionId: null,
      title: 'wingspan rules',
      sourceType: 'BASE_RULEBOOK',
      officialSourceUrl: 'https://publisher.example/wingspan_rules.pdf',
      rightsConfirmed: true,
    })
    expect(importOptions?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': 'csrf',
    })
    expect(wrapper.text()).toContain('上传完成，正在读取页面和图片')
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([
      { versionId: 'imported-version', playerCount: 4, beginnerCount: 4, durationMinutes: 25 },
    ])
    expect(FakeEventSource.instances.some((source) => source.url.includes('imported-version'))).toBe(true)
    wrapper.unmount()
  })

  it('keeps manual upload available after a safe official-import failure', async () => {
    const fetchMock = mockApplicationFetch(
      () => 'READY',
      'COMPLETED',
      [],
      undefined,
      undefined,
      () => new Response(null, { status: 422 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    await wrapper.get('input[type="url"]').setValue('https://publisher.example/not-a-pdf')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find((button) => button.text() === '下载并生成讲解')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('无法安全导入该链接')
    expect(wrapper.text()).toContain('已有 PDF')
    expect(wrapper.find('#rulebook-file').exists()).toBe(true)
    expect((wrapper.get('input[type="url"]').element as HTMLInputElement).value)
      .toBe('https://publisher.example/not-a-pdf')
    wrapper.unmount()
  })

  it('shows bounded ambiguous BGG candidates and requires an explicit keyboard-ready selection', async () => {
    let resolveSuggestions!: (response: Response) => void
    const pendingSuggestions = new Promise<Response>((resolve) => { resolveSuggestions = resolve })
    const fetchMock = mockApplicationFetch(
      () => 'READY',
      'COMPLETED',
      [],
      () => pendingSuggestions,
      () => response({ alreadyImported: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    const completeButton = wrapper.findAll('button').find((button) => button.text() === '补全桌游资料')
    expect(completeButton).toBeDefined()
    await completeButton!.trigger('click')
    expect(wrapper.text()).toContain('正在用规则书标题查找 BGG 候选')
    expect(completeButton!.attributes('disabled')).toBeDefined()

    resolveSuggestions(response([
      {
        bggId: 266192,
        name: 'Wingspan',
        publicationYear: 2019,
        coverUrl: 'https://example.test/wingspan.jpg',
        minPlayers: 1,
        maxPlayers: 5,
        playingTimeMinutes: 70,
        minimumAge: 10,
        normalizedTitleMatch: true,
        bggUrl: 'https://boardgamegeek.com/boardgame/266192',
      },
      {
        bggId: 123,
        name: 'Wingspan: Fan Edition',
        publicationYear: 2020,
        coverUrl: '',
        minPlayers: 2,
        maxPlayers: 4,
        playingTimeMinutes: 60,
        minimumAge: 10,
        normalizedTitleMatch: false,
        bggUrl: 'https://boardgamegeek.com/boardgame/123',
      },
    ]))
    await flushPromises()

    expect(wrapper.text()).toContain('找到 2 个候选，请确认')
    expect(wrapper.text()).toContain('BGG 资料只用于封面和目录展示，不会作为规则问答证据')
    expect(wrapper.findAll('button').filter((button) => button.text() === '选择此项')).toHaveLength(2)
    const selectButton = wrapper.findAll('button').find((button) => button.text() === '选择此项')!
    expect(selectButton.attributes('aria-pressed')).toBe('false')
    await selectButton.trigger('click')
    expect(selectButton.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('请再次确认后再关联')
    expect(wrapper.text()).toContain('确认关联这款桌游')
    expect(wrapper.get('a[href="https://boardgamegeek.com/boardgame/266192"]').attributes('rel')).toContain('noopener')
    await wrapper.findAll('button').find((button) => button.text() === '确认关联这款桌游')!.trigger('click')
    await flushPromises()
    const linkRequest = fetchMock.mock.calls.find(([input, options]) =>
      String(input).endsWith('/api/v1/documents/document-1/bgg-link') && options?.method === 'POST')
    expect(JSON.parse(String(linkRequest?.[1]?.body))).toEqual({ bggId: 266192 })
    expect(linkRequest?.[1]?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': 'csrf',
    })
    expect(wrapper.text()).toContain('已关联桌游资料')
    expect(wrapper.text()).toContain('开始讲解')
    wrapper.unmount()
  })

  it('keeps no-match and BGG failure states local to metadata completion', async () => {
    const noMatchFetch = mockApplicationFetch(() => 'READY', 'COMPLETED', [], () => response([]))
    vi.stubGlobal('fetch', noMatchFetch)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '补全桌游资料')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('没有找到同名桌游')
    expect(wrapper.text()).toContain('开始讲解')
    wrapper.unmount()

    const failingFetch = mockApplicationFetch(
      () => 'READY',
      'COMPLETED',
      [],
      () => new Response(null, { status: 503 }),
    )
    vi.stubGlobal('fetch', failingFetch)
    const second = await mountDocuments()
    await flushPromises()
    await second.wrapper.findAll('button').find((button) => button.text() === '补全桌游资料')!.trigger('click')
    await flushPromises()
    expect(second.wrapper.text()).toContain('规则书和讲解不受影响')
    expect(second.wrapper.text()).toContain('重试查找')
    expect(second.wrapper.text()).toContain('开始讲解')
    second.wrapper.unmount()
  })

  it('reports reused and failed final links without hiding the ready guide action', async () => {
    const suggestion = () => response([{
      bggId: 266192,
      name: 'Wingspan',
      publicationYear: 2019,
      coverUrl: '',
      minPlayers: 1,
      maxPlayers: 5,
      playingTimeMinutes: 70,
      minimumAge: 10,
      normalizedTitleMatch: true,
      bggUrl: 'https://boardgamegeek.com/boardgame/266192',
    }])
    vi.stubGlobal('fetch', mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], suggestion, () => response({ alreadyImported: true }),
    ))
    vi.stubGlobal('EventSource', FakeEventSource)
    const reused = await mountDocuments()
    await flushPromises()
    await reused.wrapper.findAll('button').find((button) => button.text() === '补全桌游资料')!.trigger('click')
    await flushPromises()
    await reused.wrapper.findAll('button').find((button) => button.text() === '选择此项')!.trigger('click')
    await reused.wrapper.findAll('button').find((button) => button.text() === '确认关联这款桌游')!.trigger('click')
    await flushPromises()
    expect(reused.wrapper.text()).toContain('已复用现有桌游资料并完成关联')
    expect(reused.wrapper.text()).toContain('开始讲解')
    reused.wrapper.unmount()

    vi.stubGlobal('fetch', mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], suggestion, () => new Response(null, { status: 409 }),
    ))
    const failed = await mountDocuments()
    await flushPromises()
    await failed.wrapper.findAll('button').find((button) => button.text() === '补全桌游资料')!.trigger('click')
    await flushPromises()
    await failed.wrapper.findAll('button').find((button) => button.text() === '选择此项')!.trigger('click')
    await failed.wrapper.findAll('button').find((button) => button.text() === '确认关联这款桌游')!.trigger('click')
    await flushPromises()
    expect(failed.wrapper.text()).toContain('关联失败，没有改变规则书')
    expect(failed.wrapper.text()).toContain('开始讲解')
    failed.wrapper.unmount()
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
  bggSuggestions?: () => Response | Promise<Response>,
  bggLink?: (options?: RequestInit) => Response | Promise<Response>,
  officialImport?: (options?: RequestInit) => Response | Promise<Response>,
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
    if (path.endsWith('/api/v1/documents/document-1/bgg-suggestions')) {
      return bggSuggestions ? await bggSuggestions() : new Response(null, { status: 404 })
    }
    if (path.endsWith('/api/v1/documents/document-1/bgg-link') && options?.method === 'POST') {
      return bggLink ? await bggLink(options) : new Response(null, { status: 404 })
    }
    if (path.endsWith('/api/v1/documents/official-imports') && options?.method === 'POST') {
      return officialImport ? await officialImport(options) : new Response(null, { status: 404 })
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
    if (path.includes('/teaching-plans/plan-1/illustrated-lessons')) {
      return response({ assistantRunId: 'lesson-run-1', state: 'RECEIVED', reused: false }, 202)
    }
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
