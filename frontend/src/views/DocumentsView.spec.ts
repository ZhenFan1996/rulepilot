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

  it('resumes a ready upload into the same persisted background guide flow', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1',
      learningGoal: '先让我能带大家开局，再讲容易混淆的行动。',
    })
    const fetchMock = mockApplicationFetch(() => 'READY')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments()
    await flushPromises()
    await flushPromises()

    const preparation = fetchMock.mock.calls.find(([input, options]) =>
      String(input).includes('/document-versions/version-1/teaching-plans') && options?.method === 'POST')
    expect(JSON.parse(String(preparation?.[1]?.body))).toEqual({
      learningGoal: '先让我能带大家开局，再讲容易混淆的行动。',
    })
    expect(router.currentRoute.value).toMatchObject({
      name: 'lessons', query: { started: 'plan-1', run: 'lesson-run-1' },
    })
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    wrapper.unmount()
  })

  it('shows the selected game and edition handed off from discovery', async () => {
    const openSource = vi.fn()
    vi.stubGlobal('open', openSource)
    const fetchMock = mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], undefined, undefined,
      (options) => response({
        id: 'job-selected', title: 'Catalog Game Rules', sourceDomain: 'publisher.example',
        stage: options?.method === 'POST' ? 'CONNECTING' : 'COMPLETED',
        downloadedBytes: 2048, totalBytes: 2048, documentVersionId: 'selected-version',
        duplicate: false, errorCode: null, reused: false,
        teachingHandoffState: options?.method === 'POST' ? 'WAITING_FOR_DOCUMENT' : 'LAUNCHED',
        teachingPreparationRunId: options?.method === 'POST' ? null : 'prep-selected', teachingErrorCode: null,
      }, options?.method === 'POST' ? 202 : 200),
    )
    fetchMock.mockImplementationOnce(async () => response({ username: 'player' }))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).includes('/api/v1/documents/rulebook-candidates')) return response({
        configured: true,
        candidates: [{
          title: 'Catalog Game Rules', url: 'https://publisher.example/rules.pdf', publisher: 'Publisher',
          language: 'zh-CN', edition: 'First', sourceDomain: 'publisher.example', officialDomainVerified: true,
          sourceType: 'PUBLISHER', acquisitionMode: 'DIRECT_PDF',
        }, {
          title: 'BGG 文件页', url: 'https://boardgamegeek.com/filepage/123/rules', publisher: '',
          language: 'zh-CN', edition: 'First', sourceDomain: 'boardgamegeek.com', officialDomainVerified: false,
          sourceType: 'COMMUNITY_PLATFORM', acquisitionMode: 'SOURCE_PAGE',
        }],
      })
      if (String(input).includes('/api/v1/games')) return response([{
        game: { id: 'game-1', name: 'Catalog Game' },
        editions: [{ id: 'edition-1', name: 'BGG 基础版', language: 'und' }],
        bggMetadata: { thumbnailUrl: 'https://example.test/cover.jpg', bggUrl: 'https://boardgamegeek.com/boardgame/42' },
      }])
      return fetchMock(input, options)
    }))
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments('/teach?editionId=edition-1&onboarding=selected-game')
    await flushPromises()

    expect(wrapper.text()).toContain('正在为这款桌游找规则书')
    expect(wrapper.get('img[src="/illustrations/rulebook-reading.webp"]').attributes('alt')).toBe('')
    expect(wrapper.text()).toContain('Catalog Game')
    expect(wrapper.text()).toContain('已选择版本：BGG 基础版')
    expect(wrapper.get('select').element.value).toBe('edition-1')
    expect(wrapper.findAll('input[type="number"]')).toHaveLength(0)
    expect(wrapper.findAll('label').map(label => label.text())).not.toEqual(
      expect.arrayContaining(['玩家', '新手', '分钟']),
    )
    await wrapper.findAll('button').find(button => button.text().includes('帮我找规则书'))!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Catalog Game Rules')
    expect(wrapper.text()).toContain('出版社 / 权利方来源')
    await wrapper.findAll('button').find(button => button.text() === '打开来源页')!.trigger('click')
    expect(openSource).toHaveBeenCalledWith(
      'https://boardgamegeek.com/filepage/123/rules', '_blank', 'noopener,noreferrer',
    )
    expect((wrapper.get('input[type="url"]').element as HTMLInputElement).value).toBe('')
    await wrapper.findAll('button').find(button => button.text() === '选择并继续核对')!.trigger('click')
    expect((wrapper.get('input[type="url"]').element as HTMLInputElement).value).toBe('https://publisher.example/rules.pdf')
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    const importRequest = await vi.waitFor(() => {
      const request = fetchMock.mock.calls.find(([input, options]) =>
        String(input).endsWith('/api/v1/documents/official-imports') && options?.method === 'POST')
      expect(request).toBeDefined()
      return request
    })
    expect(JSON.parse(String(importRequest?.[1]?.body))).toMatchObject({
      editionId: 'edition-1', startTeaching: true,
    })
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    expect(wrapper.text()).toContain('已进入“我的讲解”')
    wrapper.unmount()
  })

  it('treats a failed terminal progress event as failure and never starts teaching', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1',
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
      versionId: 'version-1',
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
      versionId: 'version-1',
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
      versionId: 'version-1',
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

  it('sends a free-form learning goal to the outline planner without client-side mode routing', async () => {
    vi.useFakeTimers()
    const fetchMock = mockApplicationFetch(() => 'READY', 'LESSON_PLANNING')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    expect(wrapper.text()).toContain('这次最想学会什么？')
    expect(wrapper.text()).toContain('用自然语言说就好')
    await wrapper.get('textarea[maxlength="500"]').setValue(
      '先让我能带大家开局，再重点讲行动之间怎么衔接；容易混淆的地方多举例。',
    )
    await wrapper.findAll('button').find(button => button.text() === '后台生成讲解')!.trigger('click')
    await flushPromises()

    const planningRequest = fetchMock.mock.calls.find(([input, options]) =>
      String(input).endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST')
    expect(JSON.parse(String(planningRequest?.[1]?.body))).toEqual({
      learningGoal: '先让我能带大家开局，再重点讲行动之间怎么衔接；容易混淆的地方多举例。',
    })
    wrapper.unmount()
    await vi.runOnlyPendingTimersAsync()
  })

  it('shows the real visual-reading batch instead of a silent planning wait', async () => {
    vi.useFakeTimers()
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1',
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
    expect(wrapper.text()).toContain('可选：关联游戏、规则书来源和讲解偏好')
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

  it('persists automatic teaching with the local upload instead of depending on the originating page', async () => {
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/api/v1/documents') && options?.method === 'POST') {
        return response({ duplicate: false, version: { id: 'version-1', status: 'READY' } }, 201)
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments()
    await flushPromises()

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'catalog_game_rules.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await wrapper.get('textarea[maxlength="500"]').setValue('先讲清开局。')
    await wrapper.get('form.tabletop-panel').trigger('submit')
    await flushPromises()
    await flushPromises()

    const upload = fetchMock.mock.calls.find(([request, options]) =>
      String(request).endsWith('/api/v1/documents') && options?.method === 'POST')
    const form = upload?.[1]?.body as FormData
    expect(form.get('startTeaching')).toBe('true')
    expect(form.get('learningGoal')).toBe('先讲清开局。')
    expect(fetchMock.mock.calls.some(([request, options]) =>
      String(request).endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST')).toBe(false)
    expect(router.currentRoute.value.name).toBe('teach')
    expect(wrapper.text()).toContain('你可以离开这里，处理会在后台继续')
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
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
        if (options?.method === 'POST') importOptions = options
        return response({
          id: 'job-imported', title: 'wingspan rules', sourceDomain: 'publisher.example',
          stage: options?.method === 'POST' ? 'CONNECTING' : 'COMPLETED',
          downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1',
          duplicate: false, errorCode: null, reused: false,
          teachingHandoffState: options?.method === 'POST' ? 'WAITING_FOR_DOCUMENT' : 'LAUNCHED',
          teachingPreparationRunId: options?.method === 'POST' ? null : 'prep-run-1',
          teachingErrorCode: null,
        }, options?.method === 'POST' ? 202 : 200)
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    const importButton = wrapper.findAll('button').find((button) => button.text() === '下载规则书并生成讲解')!
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
      startTeaching: true,
      learningGoal: null,
    })
    expect(importOptions?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': 'csrf',
    })
    expect(wrapper.text()).toContain('已进入“我的讲解”')
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    wrapper.unmount()
  })

  it('keeps a recovered import visible until its persisted teaching handoff really launches', async () => {
    let importReads = 0
    let releaseLaunch!: (value: Response) => void
    const launched = new Promise<Response>((resolve) => { releaseLaunch = resolve })
    const job = (handoff: 'LAUNCHING' | 'LAUNCHED') => ({
      id: 'job-recovered', title: 'Catalog Game', rulebookTitle: 'catalog_rules_final.pdf',
      sourceDomain: 'publisher.example', stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
      documentVersionId: 'version-1', duplicate: false, errorCode: null, reused: false,
      teachingHandoffState: handoff,
      teachingPreparationRunId: handoff === 'LAUNCHED' ? 'prep-run-1' : null,
      teachingErrorCode: null,
    })
    const fetchMock = mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], undefined, undefined,
      () => {
        importReads += 1
        return importReads === 1 ? response(job('LAUNCHING')) : launched
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach?importJob=job-recovered')
    await flushPromises()

    expect(router.currentRoute.value.query.importJob).toBe('job-recovered')
    expect(wrapper.text()).toContain('规则书已可阅读，正在启动讲解')
    expect(wrapper.text()).not.toContain('已进入“我的讲解”')

    releaseLaunch(response(job('LAUNCHED')))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.query.importJob).toBeUndefined()
    expect(wrapper.text()).toContain('已进入“我的讲解”')
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
    await wrapper.findAll('button').find((button) => button.text() === '下载规则书并生成讲解')!.trigger('click')
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
    expect(wrapper.text()).toContain('后台生成讲解')
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
    expect(wrapper.text()).toContain('后台生成讲解')
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
    expect(second.wrapper.text()).toContain('后台生成讲解')
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
    expect(reused.wrapper.text()).toContain('后台生成讲解')
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
    expect(failed.wrapper.text()).toContain('后台生成讲解')
    failed.wrapper.unmount()
  })
})

async function mountDocuments(path = '/teach') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: DocumentsView },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return {
    wrapper: mount(DocumentsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    }),
    router,
  }
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
    if (path.includes('/api/v1/documents/official-imports/')) {
      return officialImport ? await officialImport(options) : new Response(null, { status: 404 })
    }
    if (path.endsWith('/api/v1/documents/official-imports')) return response([])
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
