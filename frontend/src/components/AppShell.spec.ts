import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SESSION_CLEARED_EVENT, notifyLoginRequired } from '@/lib/authSession'
import { backgroundWorkStorageKeys } from '@/lib/backgroundTeachingStatus'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import AppShell from './AppShell.vue'

const playerStorageKeys = backgroundWorkStorageKeys('player')

function teachingRun(id: string, subjectId: string, state: string) {
  return { id, mode: 'TEACHING', subjectId, ownerUsername: 'player', state }
}

function preparationRun(id: string, subjectId: string, state: string) {
  return { id, mode: 'TEACHING_PREPARATION', subjectId, ownerUsername: 'player', state }
}

function ownedDocument(id: string, title: string) {
  return { id, title, createdBy: 'player' }
}

describe('AppShell', () => {
  afterEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    document.documentElement.classList.remove('dark', 'light')
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows active work across the app and announces when it ends', async () => {
    vi.useFakeTimers()
    let activeReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) {
        return response({ username: 'player' })
      }
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        return response(activeReads === 1 ? [teachingRun('run-1', 'plan-1', 'LESSON_COMPOSITION')] : [])
      }
      if (path.includes('/api/v1/assistant-runs/run-1')) {
        return response({ run: teachingRun('run-1', 'plan-1', 'COMPLETED') })
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-1', gameTitle: '星际探索' }])
      }
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
        || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/work', name: 'work-status', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      attachTo: document.body,
      slots: { default: '<p>页面内容</p>' },
      global: { plugins: [router] },
    })
    await flushPromises()

    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(wrapper.get('main').attributes()).toMatchObject({
      id: 'main-content',
      tabindex: '-1',
      'aria-label': '主要内容',
    })
    const backgroundWorkTrigger = wrapper.get('[data-testid="background-work-trigger-desktop"]')
    expect(backgroundWorkTrigger.element.parentElement?.id).toBe('background-work-desktop-trigger')
    expect(backgroundWorkTrigger.classes()).not.toContain('fixed')
    expect(wrapper.get('[data-testid="background-work-trigger-mobile"]').element.closest('header')).not.toBeNull()
    const persistentShortcut = wrapper.get('[data-testid="background-work-persistent-shortcut"]')
    expect(persistentShortcut.text()).toContain('讲解状态')
    expect(persistentShortcut.text()).toContain('《星际探索》仍在后台准备')
    expect(persistentShortcut.text()).toContain('看进度')
    expect(wrapper.get('[data-testid="work-status-navigation-link"]').attributes('href')).toBe('/work')
    expect(wrapper.get('[data-testid="work-status-navigation-active"]').text()).toBe('1')
    await persistentShortcut.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('星际探索')
    expect(wrapper.text()).toContain('可以继续浏览')
    expect(wrapper.text()).toContain('公开讲解')
    expect(wrapper.text()).not.toContain('我的规则书')
    expect(wrapper.text()).toContain('我的讲解')
    expect(backgroundWorkTrigger.text()).toContain('1')
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('中文')
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('EN')
    expect(document.activeElement).toBe(wrapper.get('button[aria-label="关闭后台任务"]').element)
    expect(document.body.style.overflow).toBe('hidden')

    vi.advanceTimersByTime(4000)
    await flushPromises()

    expect(wrapper.text()).toContain('讲解完成')
    expect(wrapper.text()).not.toContain('生成成功')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/teaching-plans'))).toHaveLength(1)

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()
    expect(document.activeElement).toBe(persistentShortcut.element)
    expect(document.body.style.overflow).toBe('')
    await backgroundWorkTrigger.trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('星际探索')
    expect(JSON.parse(localStorage.getItem(playerStorageKeys.dismissedTeachingRuns) ?? '[]'))
      .toContain('run-1')
    wrapper.unmount()
  })

  it('keeps an explicit light choice when the device prefers dark appearance', async () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' },
      global: { plugins: [router] },
    })
    await flushPromises()

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    const mobileAppearanceControl = wrapper.get('header button[aria-label="切换到浅色模式"]')
    expect(mobileAppearanceControl.attributes('aria-pressed')).toBe('true')
    await mobileAppearanceControl.trigger('click')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(localStorage.getItem('rulepilot:appearance-preference')).toBe('light')

    wrapper.unmount()
    document.documentElement.classList.remove('dark', 'light')
    const remounted = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' },
      global: { plugins: [router] },
    })
    await flushPromises()

    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    remounted.unmount()
  })

  it('does not announce completion from a transient empty active-run response', async () => {
    vi.useFakeTimers()
    sessionStorage.setItem(playerStorageKeys.activeTeaching, JSON.stringify([
      { runId: 'run-1', planId: 'plan-1', gameTitle: '星际探索' },
    ]))
    let exactReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.includes('/api/v1/assistant-runs/run-1')) {
        exactReads += 1
        return exactReads === 1
          ? new Response(null, { status: 503 })
          : response({ run: teachingRun('run-1', 'plan-1', 'COMPLETED') })
      }
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
        || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('星际探索')
    expect(wrapper.text()).not.toContain('后台处理已经结束')

    await vi.advanceTimersByTimeAsync(4000)
    await flushPromises()

    expect(wrapper.text()).toContain('讲解完成')
    expect(wrapper.find('[data-testid="work-status-navigation-finished"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="background-work-persistent-shortcut"]').text())
      .toContain('《星际探索》的后台处理已经结束')
    wrapper.unmount()
  })

  it('starts background status tracking immediately when teaching is launched', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.includes('/api/v1/assistant-runs/run-2')) {
        return response({ run: teachingRun('run-2', 'plan-2', 'RECEIVED') })
      }
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
        || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('[data-testid="background-work-trigger-desktop"]').text()).not.toContain('1')
    expect(wrapper.find('[data-testid="work-status-navigation-active"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="background-work-persistent-shortcut"]').exists()).toBe(false)

    notifyTeachingLaunched({ planId: 'plan-2', runId: 'run-2', gameTitle: '卡坦岛' })
    await flushPromises()

    expect(wrapper.get('[data-testid="work-status-navigation-active"]').text()).toBe('1')
    expect(wrapper.get('[data-testid="background-work-persistent-shortcut"]').text())
      .toContain('《卡坦岛》仍在后台准备')
    await wrapper.get('[data-testid="background-work-persistent-shortcut"]').trigger('click')
    expect(wrapper.text()).toContain('卡坦岛')
    expect(sessionStorage.getItem(playerStorageKeys.activeTeaching)).toContain('run-2')
    wrapper.unmount()
  })

  it('shows the persisted recommendation handoff until real guide preparation is running', async () => {
    const updatedAt = new Date().toISOString()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/preparation-run-1')) {
        return response({ run: preparationRun('preparation-run-1', 'version-launched', 'LESSON_PLANNING') })
      }
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-waiting', title: '卡坦岛规则书', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-waiting', errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        teachingErrorCode: null, updatedAt,
      }, {
        id: 'import-launched', title: '展翅翱翔规则书', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-launched', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-run-1',
        teachingErrorCode: null, updatedAt,
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: ownedDocument('document-waiting', '卡坦岛规则书'),
        latestVersion: { id: 'version-waiting', status: 'READY' },
      }, {
        document: ownedDocument('document-launched', '展翅翱翔规则书'),
        latestVersion: { id: 'version-launched', status: 'READY' },
      }])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()

    const trigger = wrapper.get('[data-testid="background-work-trigger-desktop"]')
    expect(trigger.text()).toContain('2')
    const persistentShortcut = wrapper.get('[data-testid="background-work-persistent-shortcut"]')
    expect(persistentShortcut.text()).toContain('2 份讲解仍在后台准备')
    await persistentShortcut.trigger('click')

    expect(wrapper.text()).toContain('规则书已保存，读取完成后会自动开始讲解')
    expect(wrapper.text()).toContain('读取规则书')
    expect(wrapper.text()).toContain('正在组织讲解')
    expect(wrapper.text()).not.toContain('建立讲解结构')
    expect(wrapper.text()).toContain('卡坦岛规则书')
    expect(wrapper.text()).toContain('展翅翱翔规则书')
    expect(wrapper.findAll('ol li')).toHaveLength(2)
    wrapper.unmount()
  })

  it('tracks a local upload from the persisted server handoff without duplicating its document task', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([{
        id: 'handoff-1', documentVersionId: 'version-1', title: '星际探险',
        rulebookTitle: 'rules_v4_final.pdf', state: 'WAITING_FOR_DOCUMENT',
        preparationRunId: null, errorCode: null, updatedAt: new Date().toISOString(),
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: ownedDocument('document-1', 'rules_v4_final.pdf'),
        latestVersion: { id: 'version-1', status: 'EXTRACTING' },
      }])
      if (path.includes('/document-versions/version-1/progress/snapshot')) return response({
        stage: 'EXTRACTING', percentage: 35, processedPages: 3, totalPages: 12, complete: false,
      })
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] },
    })
    await flushPromises()

    const trigger = wrapper.get('[data-testid="background-work-trigger-desktop"]')
    expect(trigger.text()).toContain('1')
    await trigger.trigger('click')
    expect(wrapper.text()).toContain('星际探险')
    expect(wrapper.text()).toContain('rules_v4_final.pdf')
    expect(wrapper.text()).toContain('正在提取规则文字')
    expect(wrapper.findAll('ol li')).toHaveLength(1)
    wrapper.unmount()
  })

  it('clears failed import preparation and keeps it dismissed after the server refreshes', async () => {
    vi.useFakeTimers()
    const updatedAt = new Date().toISOString()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/teaching-preparation-failures/official-imports/')) {
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/v1/assistant-runs/preparation-failed')) {
        return response({ run: preparationRun('preparation-failed', 'version-import-failed', 'FAILED') })
      }
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-failed-preparation', title: '失败的官方讲解', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-import-failed', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-failed',
        teachingErrorCode: null, updatedAt,
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: ownedDocument('document-import-failed', 'official-rules.pdf'),
        latestVersion: { id: 'version-import-failed', status: 'READY' },
      }])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('失败的官方讲解')
    expect(wrapper.text()).toContain('需要处理')
    expect(wrapper.text()).toContain('讲解准备已经停止，请在讲解中心查看失败原因')

    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('失败的官方讲解')
    expect(sessionStorage.getItem(playerStorageKeys.dismissedImports)).toContain('import-failed-preparation')
    expect(localStorage.getItem(playerStorageKeys.dismissedImports)).toContain('import-failed-preparation')

    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('失败的官方讲解')
    wrapper.unmount()
  })

  it('does not pre-dismiss an active import preparation while clearing another failure', async () => {
    vi.useFakeTimers()
    let activePreparationReads = 0
    const updatedAt = new Date().toISOString()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/teaching-preparation-failures/official-imports/')) {
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/v1/assistant-runs/preparation-active')) {
        activePreparationReads += 1
        return response({
          run: preparationRun(
            'preparation-active',
            'version-active',
            activePreparationReads === 1 ? 'LESSON_PLANNING' : 'FAILED',
          ),
        })
      }
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-active-preparation', title: '仍在准备的讲解', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-active', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-active',
        teachingErrorCode: null, updatedAt,
      }, {
        id: 'import-download-failed', title: '下载失败的规则书', sourceDomain: 'publisher.example',
        stage: 'FAILED', downloadedBytes: 0, totalBytes: null,
        documentVersionId: null, errorCode: 'SOURCE_UNAVAILABLE',
        teachingHandoffState: 'FAILED', teachingPreparationRunId: null,
        teachingErrorCode: 'SOURCE_UNAVAILABLE', updatedAt,
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: ownedDocument('document-active', 'active-rules.pdf'),
        latestVersion: { id: 'version-active', status: 'READY' },
      }])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('仍在准备的讲解')
    expect(wrapper.text()).toContain('下载失败的规则书')
    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('仍在准备的讲解')
    expect(wrapper.text()).not.toContain('下载失败的规则书')
    expect(sessionStorage.getItem(playerStorageKeys.dismissedImports))
      .not.toContain('import-active-preparation')

    await vi.advanceTimersByTimeAsync(4000)
    await flushPromises()
    expect(wrapper.text()).toContain('仍在准备的讲解')
    expect(wrapper.text()).toContain('需要处理')
    expect(wrapper.text()).toContain('讲解准备已经停止，请在讲解中心查看失败原因')
    wrapper.unmount()
  })

  it('clears a failed uploaded document handoff and keeps it dismissed after remount', async () => {
    const updatedAt = new Date().toISOString()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/teaching-preparation-failures/uploads/')) {
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([{
        id: 'upload-handoff-failed-document', documentVersionId: 'version-upload-failed', title: '失败的上传讲解',
        rulebookTitle: 'broken-rules.pdf', state: 'WAITING_FOR_DOCUMENT',
        preparationRunId: null, errorCode: 'DOCUMENT_PROCESSING_FAILED', updatedAt,
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: ownedDocument('document-upload-failed', 'broken-rules.pdf'),
        latestVersion: { id: 'version-upload-failed', status: 'FAILED' },
      }])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const mountShell = () => mount(AppShell, {
      slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] },
    })
    const wrapper = mountShell()
    await flushPromises()

    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('失败的上传讲解')
    expect(wrapper.text()).toContain('规则书读取失败')
    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('失败的上传讲解')
    expect(sessionStorage.getItem(playerStorageKeys.dismissedUploadHandoffs))
      .toContain('upload-handoff-failed-document')
    wrapper.unmount()

    const remounted = mountShell()
    await flushPromises()
    await remounted.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(remounted.text()).not.toContain('失败的上传讲解')
    expect(remounted.text()).toContain('当前没有后台任务')
    remounted.unmount()
  })

  it('clears a terminal upload handoff even when its deleted document is absent from the recent list', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/teaching-preparation-failures/uploads/')) {
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([{
        id: 'orphaned-failed-handoff', documentVersionId: 'missing-version', title: '已删除规则书的失败任务',
        rulebookTitle: 'removed-rules.pdf', state: 'WAITING_FOR_DOCUMENT',
        preparationRunId: null, errorCode: 'DOCUMENT_PROCESSING_FAILED', updatedAt: new Date().toISOString(),
      }])
      if (path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] },
    })
    await flushPromises()

    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('已删除规则书的失败任务')
    expect(wrapper.text()).toContain('规则书读取失败')
    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('已删除规则书的失败任务')
    wrapper.unmount()
  })

  it('discovers teaching launched in another tab while the signed-in shell is idle', async () => {
    vi.useFakeTimers()
    let activeReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        return response(activeReads === 1 ? [] : [teachingRun('run-other-tab', 'plan-other-tab', 'RECEIVED')])
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-other-tab', gameTitle: '跨标签页规则书' }])
      }
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
        || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('跨标签页规则书')
    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()
    expect(activeReads).toBe(2)
    await wrapper.get('[data-testid="background-work-trigger-desktop"]').trigger('click')
    expect(wrapper.text()).toContain('跨标签页规则书')
    expect(sessionStorage.getItem(playerStorageKeys.activeTeaching)).toContain('run-other-tab')
    wrapper.unmount()
  })

  it('keeps the current page and offers an explicit return-aware sign-in action', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>保留的页面</p>' },
      global: { plugins: [router] },
    })
    await flushPromises()

    notifyLoginRequired()
    await wrapper.vm.$nextTick()

    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    expect(wrapper.text()).toContain('当前页面已保留')
    expect(wrapper.text()).toContain('保留的页面')
    expect(wrapper.get('main a[href="/login?redirect=/lessons?filter=pending"]')).toBeTruthy()
    expect(wrapper.get('header a[href="/login?redirect=/lessons?filter=pending"]').text()).toBe('登录')
    wrapper.unmount()
  })

  it('leaves authentication recovery to one page-owned region when requested', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const router = createAppShellRouter()
    await router.push('/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(AppShell, {
      props: { loginActionOwned: true },
      slots: {
        default: '<a data-testid="owned-login-action" href="/login?redirect=/lessons?filter=pending">登录后继续</a>',
      },
      global: { plugins: [router] },
    })
    await flushPromises()

    notifyLoginRequired()
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('a[href^="/login"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="owned-login-action"]').text()).toBe('登录后继续')
    expect(wrapper.text()).not.toContain('当前页面已保留')
    wrapper.unmount()
  })

  it('clears account-owned notices and the active route state after logout succeeds', async () => {
    const sessionCleared = vi.fn()
    window.addEventListener(SESSION_CLEARED_EVENT, sessionCleared)
    sessionStorage.setItem(playerStorageKeys.activeTeaching, JSON.stringify([
      { runId: 'run-1', planId: 'plan-1', gameTitle: 'Private lesson' },
    ]))
    sessionStorage.setItem(playerStorageKeys.dismissedImports, JSON.stringify(['private-import']))
    sessionStorage.setItem(playerStorageKeys.dismissedUploadHandoffs, JSON.stringify(['private-handoff']))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.includes('/api/auth/csrf')) return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      if (path.includes('/api/auth/logout')) return new Response(null, { status: 204 })
      return new Response(null, { status: 404 })
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
      ],
    })
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>私人讲解列表</p>' }, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '退出登录')!.trigger('click')
    await flushPromises()

    expect(sessionCleared).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem(playerStorageKeys.activeTeaching)).toBeNull()
    expect(sessionStorage.getItem(playerStorageKeys.dismissedImports)).toBeNull()
    expect(sessionStorage.getItem(playerStorageKeys.dismissedUploadHandoffs)).toBeNull()
    expect(wrapper.text()).not.toContain('player')
    window.removeEventListener(SESSION_CLEARED_EVENT, sessionCleared)
    wrapper.unmount()
  })

  it('exposes the normalized shell-owned session identity without requiring a duplicate read', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return response({ username: '  Player  ', roles: ['USER'] })
      if (String(input).includes('/api/v1/assistant-runs/active')) return response([])
      if (String(input).endsWith('/api/v1/documents/official-imports')
        || String(input).endsWith('/api/v1/documents/upload-teaching-handoffs')
        || String(input).endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      slots: { default: '<p>页面内容</p>' },
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await flushPromises()

    expect(wrapper.emitted('sessionIdentity')).toEqual([['Player']])
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/session'))).toHaveLength(1)
    wrapper.unmount()
  })

  it('does not mount the duplicate background reader on either work-status route', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return response({ username: 'player', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    for (const path of ['/lessons', '/work']) {
      await router.push(path)
      await router.isReady()
      const wrapper = mount(AppShell, {
        slots: { default: '<p>讲解与任务页面</p>' },
        global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
      })
      await flushPromises()

      expect(wrapper.find('background-work-center-stub').exists()).toBe(false)
      expect(wrapper.find('[data-testid="background-work-persistent-shortcut"]').exists()).toBe(false)
      wrapper.unmount()
    }
  })
})

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createAppShellRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/work', name: 'work-status', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
}
