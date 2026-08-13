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
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each([
    ['session identity before route resources', true],
    ['route resources before session identity', false],
  ])('recovers once after %s and keeps AppShell as the only session owner', async (_label, identityFirst) => {
    rememberPendingRulebookLesson(localStorage, 'player', { versionId: 'version-1' })
    let releaseIdentity!: () => void
    let releaseResources!: () => void
    const identityGate = new Promise<void>((resolve) => { releaseIdentity = resolve })
    const resourceGate = new Promise<void>((resolve) => { releaseResources = resolve })
    const resourceStarts = new Set<string>()
    let sessionReads = 0
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/session') {
        sessionReads += 1
        await identityGate
      }
      if (path === '/api/v1/games' || path === '/api/v1/model-configuration' || path === '/api/v1/documents') {
        resourceStarts.add(path)
        await resourceGate
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await vi.waitFor(() => expect(resourceStarts.size).toBe(3))
    if (identityFirst) releaseIdentity()
    else releaseResources()
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([input, options]) =>
      String(input).endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST')).toHaveLength(0)

    if (identityFirst) releaseResources()
    else releaseIdentity()
    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([input, options]) =>
      String(input).endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST')).toHaveLength(1))

    expect(sessionReads).toBe(1)
    wrapper.unmount()
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
    vi.useFakeTimers()
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
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
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

  it('names the fast cited-page selection instead of implying that the whole PDF is being summarized', async () => {
    vi.useFakeTimers()
    rememberPendingRulebookLesson(localStorage, 'player', {
      versionId: 'version-1',
    })
    const fetchMock = mockApplicationFetch(() => 'READY', 'LESSON_PLANNING', [{
      sequence: 1, operation: 'selectProgressiveTeachingStart', outcome: 'RUNNING',
    }])
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)

    const { wrapper } = await mountDocuments()
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('正在从规则书中确认第一段可讲、可引用的玩法')
    expect(wrapper.text()).not.toContain('页面要点已经读完')
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

    const titleInput = wrapper.get('input[maxlength="160"]')
    await titleInput.setValue('English draft')
    expect(wrapper.get('[data-testid="rulebook-intake-unsaved"]').text()).toContain('Not submitted: title and document type')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent)
      .toContain('Discard this rulebook draft and leave?')
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('Keep preparing'))!.click()
    await flushPromises()
    await titleInput.setValue('')
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

  it('shows an in-memory intake draft, keeps it on cancel, and leaves only after explicit discard', async () => {
    vi.stubGlobal('fetch', mockApplicationFetch(() => 'READY'))
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'carefully_selected_rules.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await wrapper.get('input[maxlength="160"]').setValue('仔细选好的规则书')
    await wrapper.get('textarea[maxlength="500"]').setValue('先学会开局')

    const status = wrapper.get('[data-testid="rulebook-intake-unsaved"]')
    expect(status.text()).toContain('PDF“carefully_selected_rules.pdf”')
    expect(status.text()).toContain('标题与资料类型')
    expect(status.text()).toContain('讲解目标')
    expect(status.text()).toContain('只保留在当前页面')
    expect(localStorage.getItem('carefully_selected_rules.pdf')).toBeNull()
    expect(sessionStorage.getItem('carefully_selected_rules.pdf')).toBeNull()

    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/teach')
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('放弃这次规则书草稿并离开')
    expect(document.activeElement?.textContent).toContain('继续准备')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('继续准备'))!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/teach')
    expect(document.activeElement).toBe(opener.element)
    expect((wrapper.get('#rulebook-file').element as HTMLInputElement).files?.[0]?.name)
      .toBe('carefully_selected_rules.pdf')
    expect((wrapper.get('textarea[maxlength="500"]').element as HTMLTextAreaElement).value).toBe('先学会开局')

    await opener.trigger('click')
    await flushPromises()
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('放弃草稿并离开'))!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/catalog')
    wrapper.unmount()
  })

  it('protects browser unload only before the intake reaches a durable handoff', async () => {
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/api/v1/documents') && options?.method === 'POST') {
        return response({ duplicate: false, version: { id: 'version-1', status: 'UPLOADED' } }, 201)
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await flushPromises()

    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'unload-protected.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)

    await wrapper.get('form.tabletop-panel').trigger('submit')
    await flushPromises()
    const handedOff = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(handedOff)
    expect(handedOff.defaultPrevented).toBe(false)
    wrapper.unmount()

    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })

  it('waits for upload acceptance before following a requested route', async () => {
    let releaseUpload!: (value: Response) => void
    const uploadResponse = new Promise<Response>((resolve) => { releaseUpload = resolve })
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/api/v1/documents') && options?.method === 'POST') return uploadResponse
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'handoff.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await wrapper.get('form.tabletop-panel').trigger('submit')
    await wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/teach')
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('正在完成规则书交接')
    expect([...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .every(button => button.disabled)).toBe(true)

    releaseUpload(response({ duplicate: false, version: { id: 'version-1', status: 'UPLOADED' } }, 201))
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/catalog')
    expect(fetchMock.mock.calls.filter(([request]) => String(request).endsWith('/api/v1/documents'))).toHaveLength(2)
  })

  it('cancels navigation and retains the retryable upload draft when server acceptance fails', async () => {
    let releaseUpload!: (value: Response) => void
    const uploadResponse = new Promise<Response>((resolve) => { releaseUpload = resolve })
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/api/v1/documents') && options?.method === 'POST') return uploadResponse
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'retry-me.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await wrapper.get('form.tabletop-panel').trigger('submit')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')
    releaseUpload(new Response(null, { status: 503 }))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/teach')
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.text()).toContain('暂时无法处理规则书')
    expect((wrapper.get('#rulebook-file').element as HTMLInputElement).files?.[0]?.name).toBe('retry-me.pdf')
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('follows the requested route immediately after an official import is durably accepted', async () => {
    let releaseImport!: (value: Response) => void
    const importResponse = new Promise<Response>((resolve) => { releaseImport = resolve })
    const fetchMock = mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], undefined, undefined,
      options => options?.method === 'POST' ? importResponse : new Response(null, { status: 404 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    await wrapper.get('input[type="url"]').setValue('https://publisher.example/accepted.pdf')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    await opener.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('正在完成规则书交接')

    releaseImport(response({
      id: 'import-job-accepted', title: 'Accepted rules', sourceDomain: 'publisher.example', stage: 'QUEUED',
      downloadedBytes: 0, totalBytes: null, documentVersionId: null, duplicate: false, errorCode: null, reused: false,
      teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null, teachingErrorCode: null,
    }, 202))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/catalog')
    expect(fetchMock.mock.calls.some(([request]) => String(request).includes('/official-imports/import-job-accepted')))
      .toBe(false)
  })

  it('keeps a separate local draft when an official import succeeds during requested navigation', async () => {
    let releaseImport!: (value: Response) => void
    const importResponse = new Promise<Response>((resolve) => { releaseImport = resolve })
    const fetchMock = mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], undefined, undefined,
      options => options?.method === 'POST' ? importResponse : new Response(null, { status: 404 }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const input = wrapper.get('#rulebook-file')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['%PDF-1.7'], 'separate-local.pdf', { type: 'application/pdf' })],
    })
    await input.trigger('change')
    await wrapper.get('input[type="url"]').setValue('https://publisher.example/imported.pdf')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find(button => button.text() === '下载规则书并生成讲解')!.trigger('click')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')

    releaseImport(response({
      id: 'import-job-with-local-draft', title: 'Imported rules', sourceDomain: 'publisher.example', stage: 'QUEUED',
      downloadedBytes: 0, totalBytes: null, documentVersionId: null, duplicate: false, errorCode: null, reused: false,
      teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null, teachingErrorCode: null,
    }, 202))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/teach')
    expect(router.currentRoute.value.query.importJob).toBeUndefined()
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect((wrapper.get('#rulebook-file').element as HTMLInputElement).files?.[0]?.name).toBe('separate-local.pdf')
    expect(document.activeElement).toBe(opener.element)

    await opener.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('放弃这次规则书草稿并离开')
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('继续准备'))!.click()
    await flushPromises()
    expect(wrapper.get('[data-testid="rulebook-intake-unsaved"]').text()).toContain('separate-local.pdf')
    wrapper.unmount()
  })

  it('follows the requested route once a manual guide launch has a durable assistant run', async () => {
    let releaseLaunch!: (value: Response) => void
    const launchResponse = new Promise<Response>((resolve) => { releaseLaunch = resolve })
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/document-versions/version-1/teaching-plans') && options?.method === 'POST') {
        return launchResponse
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    await wrapper.get('textarea[maxlength="500"]').setValue('重点讲清第一轮')
    await wrapper.findAll('button').find(button => button.text() === '后台生成讲解')!.trigger('click')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    await opener.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('正在完成规则书交接')

    releaseLaunch(response({ assistantRunId: 'accepted-run', state: 'RECEIVED', reused: false }, 202))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/catalog')
    expect(fetchMock.mock.calls.some(([request]) => String(request).includes('/assistant-runs/accepted-run'))).toBe(false)
  })

  it('keeps photographed-page object URLs on cancel and releases them on explicit discard', async () => {
    const revokeObjectUrl = vi.fn()
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 900, height: 1200, close: vi.fn() })))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:prepared-rulebook-page')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revokeObjectUrl)
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn(),
    } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(
      new Blob(['prepared-photo'], { type: 'image/jpeg' }),
    ))
    vi.stubGlobal('fetch', mockApplicationFetch(() => 'READY'))
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const gallery = wrapper.get('#rulebook-gallery')
    Object.defineProperty(gallery.element, 'files', {
      configurable: true,
      value: [new File(['photo'], 'page-one.png', { type: 'image/png' })],
    })
    await gallery.trigger('change')
    await flushPromises()
    expect(wrapper.get('[data-testid="rulebook-intake-unsaved"]').text()).toContain('1 页照片')

    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    await opener.trigger('click')
    await flushPromises()
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('继续准备'))!.click()
    await flushPromises()
    expect(revokeObjectUrl).not.toHaveBeenCalled()
    expect(wrapper.find('img[src="blob:prepared-rulebook-page"]').exists()).toBe(true)

    await opener.trigger('click')
    await flushPromises()
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('放弃草稿并离开'))!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/catalog')
    expect(revokeObjectUrl).toHaveBeenCalledTimes(1)
  })

  it('cancels a requested route after photo preparation and keeps the newly prepared page', async () => {
    let releaseBitmap!: (value: { width: number; height: number; close: () => void }) => void
    const bitmap = new Promise<{ width: number; height: number; close: () => void }>(resolve => {
      releaseBitmap = resolve
    })
    vi.stubGlobal('createImageBitmap', vi.fn(() => bitmap))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:prepared-after-navigation')
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn(),
    } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(
      new Blob(['prepared-photo'], { type: 'image/jpeg' }),
    ))
    vi.stubGlobal('fetch', mockApplicationFetch(() => 'READY'))
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments('/teach', true)
    await flushPromises()

    const gallery = wrapper.get('#rulebook-gallery')
    Object.defineProperty(gallery.element, 'files', {
      configurable: true,
      value: [new File(['photo'], 'slow-page.png', { type: 'image/png' })],
    })
    await gallery.trigger('change')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('正在完成规则书交接')

    releaseBitmap({ width: 900, height: 1200, close: vi.fn() })
    await flushPromises()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/teach')
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.get('[data-testid="rulebook-intake-unsaved"]').text()).toContain('1 页照片')
    expect(wrapper.find('img[src="blob:prepared-after-navigation"]').exists()).toBe(true)
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('requires a direct URL and explicit rights confirmation before importing an official PDF', async () => {
    vi.useFakeTimers()
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

    expect(fetchMock.mock.calls.filter(([input, options]) =>
      String(input).includes('/api/v1/documents/official-imports/') && options?.method !== 'POST')).toHaveLength(0)

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
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(wrapper.text()).toContain('已进入“我的讲解”')
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
    expect(fetchMock.mock.calls.filter(([input, options]) =>
      String(input).includes('/api/v1/documents/official-imports/') && options?.method !== 'POST')).toHaveLength(1)
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/v1/documents')).toHaveLength(2)
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/auth/session')).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([input]) => /cancel|cancellation/i.test(String(input)))).toBe(false)
    wrapper.unmount()
  })

  it('keeps a recovered import visible until its persisted teaching handoff really launches', async () => {
    vi.useFakeTimers()
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

    await vi.advanceTimersByTimeAsync(1_000)
    releaseLaunch(response(job('LAUNCHED')))
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.query.importJob).toBeUndefined()
    expect(wrapper.text()).toContain('已进入“我的讲解”')
    wrapper.unmount()
  })

  it('aborts only the recovered import read on unmount and ignores its late settlement', async () => {
    let releaseImport!: (value: Response) => void
    const importResponse = new Promise<Response>((resolve) => { releaseImport = resolve })
    let importSignal: AbortSignal | undefined
    const fetchMock = mockApplicationFetch(
      () => 'READY', 'COMPLETED', [], undefined, undefined,
      (options) => {
        importSignal = options?.signal ?? undefined
        return importResponse
      },
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments('/teach?importJob=job-late')
    await vi.waitFor(() => expect(importSignal).toBeDefined())
    const callsBeforeUnmount = fetchMock.mock.calls.length

    wrapper.unmount()
    expect(importSignal?.aborted).toBe(true)
    releaseImport(response({
      id: 'job-late', title: 'Late rules', sourceDomain: 'publisher.example', stage: 'COMPLETED',
      downloadedBytes: 1024, totalBytes: 1024, documentVersionId: 'version-1', duplicate: false,
      errorCode: null, reused: false, teachingHandoffState: 'LAUNCHED',
      teachingPreparationRunId: 'prep-late', teachingErrorCode: null,
    }))
    await flushPromises()

    expect(fetchMock.mock.calls).toHaveLength(callsBeforeUnmount)
    expect(fetchMock.mock.calls.some(([input]) => /cancel|cancellation/i.test(String(input)))).toBe(false)
  })

  it('rejects a preparation run whose subject is another document version', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', { versionId: 'version-1' })
    const applicationFetch = mockApplicationFetch(() => 'READY')
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).includes('/api/v1/assistant-runs/prep-run-1')) {
        return response({
          run: { id: 'prep-run-1', subjectId: 'version-other', state: 'COMPLETED', lastErrorCode: null },
          activities: [],
        })
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper, router } = await mountDocuments()

    await vi.waitFor(() => expect(wrapper.text()).toContain('暂时无法处理规则书'))
    expect(router.currentRoute.value.path).toBe('/teach')
    expect(fetchMock.mock.calls.some(([input]) =>
      String(input).endsWith('/document-versions/version-1/teaching-plans/latest'))).toBe(false)
    expect(fetchMock.mock.calls.some(([input, options]) =>
      String(input).includes('/illustrated-lessons') && options?.method === 'POST')).toBe(false)
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{ versionId: 'version-1' }])
    wrapper.unmount()
  })

  it('closes document progress on unmount and ignores a buffered terminal event', async () => {
    rememberPendingRulebookLesson(localStorage, 'player', { versionId: 'version-1' })
    const fetchMock = mockApplicationFetch(() => 'EXTRACTING')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments()
    await vi.waitFor(() => expect(FakeEventSource.instances).toHaveLength(1))
    const callsBeforeUnmount = fetchMock.mock.calls.length
    const progressSource = FakeEventSource.instances[0]!

    wrapper.unmount()
    expect(progressSource.closed).toBe(true)
    progressSource.emitProgress({
      stage: 'READY', percentage: 100, processedPages: 12, totalPages: 12, complete: true,
    })
    await flushPromises()

    expect(fetchMock.mock.calls).toHaveLength(callsBeforeUnmount)
    expect(fetchMock.mock.calls.some(([input]) => /cancel|cancellation/i.test(String(input)))).toBe(false)
    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{ versionId: 'version-1' }])
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

  it('keeps a rulebook until the destructive dialog is confirmed and preserves it for a failed retry', async () => {
    const applicationFetch = mockApplicationFetch(() => 'READY')
    let deleteAttempts = 0
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      if (String(input).endsWith('/api/v1/documents/document-1') && options?.method === 'DELETE') {
        deleteAttempts += 1
        return new Response(null, { status: deleteAttempts === 1 ? 503 : 204 })
      }
      return applicationFetch(input, options)
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('EventSource', FakeEventSource)
    const { wrapper } = await mountDocuments('/teach', true)
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '删除')!.trigger('click')
    await flushPromises()
    expect(deleteAttempts).toBe(0)
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('测试规则书')
    expect(document.activeElement?.textContent).toContain('保留规则书')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('删除规则书'))!.click()
    await flushPromises()
    expect(deleteAttempts).toBe(1)
    expect(wrapper.text()).toContain('测试规则书')
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('暂时无法处理规则书')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('重新尝试删除'))!.click()
    await flushPromises()
    await flushPromises()
    expect(deleteAttempts).toBe(2)
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.text()).not.toContain('测试规则书')
    expect(wrapper.text()).toContain('规则书和它生成的讲解已经删除')
    expect(document.activeElement).toBe(wrapper.findAll('h2').find(heading => heading.text().includes('已上传的规则书'))!.element)
    wrapper.unmount()
  })
})

async function mountDocuments(path = '/teach', attachToBody = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
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
      ...(attachToBody ? { attachTo: document.body } : {}),
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
        run: {
          id: 'prep-run-1', subjectId: 'version-1', state: preparationState, lastErrorCode: null,
        },
        activities: preparationActivities,
      })
    }
    if (path.endsWith('/document-versions/version-1/teaching-plans/latest')) {
      return response({ id: 'plan-1', documentVersionId: 'version-1' })
    }
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
  closed = false
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

  close() { this.closed = true }
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
