import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { LOGIN_REQUIRED_EVENT } from '@/lib/authSession'

import LessonsView from './LessonsView.vue'

describe('LessonsView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('shows one route-owned sign-in recovery instead of reload and upload noise', async () => {
    const loginRequired = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    const router = createMemoryRouter()
    await router.push('/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await flushPromises()

    const gate = wrapper.get('[data-testid="signed-out-guides-gate"]')
    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    expect(gate.get('h2').text()).toBe('登录后查看你的讲解')
    expect(gate.text()).toContain('登录后回到这里')
    expect(wrapper.findAll('a[href="/login?redirect=/lessons?filter=pending"]')).toHaveLength(1)
    expect(wrapper.find('main a[href="/teach"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === '重新加载')).toBe(false)
    expect(wrapper.text()).not.toContain('当前页面已保留')
    expect(loginRequired).toHaveBeenCalled()
    expect(loginRequired.mock.calls.every(([event]) =>
      (event as CustomEvent).detail?.showReminder === false)).toBe(true)

    window.removeEventListener(LOGIN_REQUIRED_EVENT, loginRequired)
    wrapper.unmount()
  })

  it('shows a persisted selected game before plan creation and replaces it with the real guide', async () => {
    vi.useFakeTimers()
    let planReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        planReads += 1
        return Response.json(planReads === 1 ? [] : [{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: '花砖物语', premise: '先认识目标。',
          createdAt: '2026-08-10T10:01:00Z', sections: [{
            position: 1, required: true, topicKey: 'goal', title: '游戏目标', visualEvidenceRecommended: false,
          }],
        }])
      }
      if (path === '/api/v1/documents/official-imports') return Response.json([{
        id: 'import-1', title: '花砖物语', rulebookTitle: 'azul_rules_cn_final.pdf', stage: 'COMPLETED',
        downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-1', teachingErrorCode: null,
        updatedAt: '2026-08-10T10:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([])
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'azul_rules_cn_final.pdf' }, latestVersion: { id: 'version-1' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '花砖物语' }, editions: [{ id: 'edition-1' }],
      }])
      if (path.includes('/assistant-runs/latest') || path.includes('/illustrated-lessons/latest')) {
        return new Response(null, { status: 404 })
      }
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router] } })
    await flushPromises()

    const pending = wrapper.get('[data-testid="pending-guide-journey"]')
    expect(pending.text()).toContain('花砖物语')
    expect(pending.text()).toContain('azul_rules_cn_final.pdf')
    expect(pending.get('[data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(wrapper.text()).not.toContain('还没有讲解计划')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(wrapper.find('[data-testid="pending-guide-journey"]').exists()).toBe(false)
    expect(wrapper.findAll('h2').some(heading => heading.text() === '花砖物语')).toBe(true)
    wrapper.unmount()
  })

  it('keeps observing a new plan while preparation is still starting its first readable chapter', async () => {
    vi.useFakeTimers()
    let planReads = 0
    let runReads = 0
    let lessonReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        planReads += 1
        return Response.json(planReads === 1 ? [] : [{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: '花砖物语', premise: '先认识目标。',
          createdAt: '2026-08-10T10:01:00Z', sections: [{
            position: 1, required: true, topicKey: 'goal', title: '游戏目标', visualEvidenceRecommended: false,
          }],
        }])
      }
      if (path === '/api/v1/documents/official-imports') return Response.json([{
        id: 'import-1', title: '花砖物语', stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-1', errorCode: null, teachingHandoffState: 'LAUNCHED',
        teachingPreparationRunId: 'prep-1', teachingErrorCode: null, updatedAt: '2026-08-10T10:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([{
        id: 'prep-1', subjectId: 'version-1', state: 'LESSON_PLANNING', lastErrorCode: null,
        updatedAt: '2026-08-10T10:01:00Z',
      }])
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'azul.pdf' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '花砖物语' }, editions: [{ id: 'edition-1' }],
      }])
      if (path.includes('/api/v1/assistant-runs/latest')) {
        runReads += 1
        if (runReads === 1) return new Response(null, { status: 404 })
        return Response.json({
          run: {
            id: 'run-1', subjectId: 'plan-1', state: 'LESSON_COMPOSITION', createdAt: '2026-08-10T10:01:00Z',
            updatedAt: '2026-08-10T10:01:02Z', completedAt: null, lastErrorCode: null,
          },
          budget: { usedModelCalls: 1, maxModelCalls: 12 },
          activities: [{
            sequence: 1, type: 'VALIDATION', operation: 'publishTeachingSection|1',
            summary: 'CITED_BASE_SECTION_PUBLISHED', outcome: 'SUCCEEDED', latencyMs: 12,
            occurredAt: '2026-08-10T10:01:02Z',
          }],
        })
      }
      if (path.includes('/illustrated-lessons/latest')) {
        lessonReads += 1
        if (lessonReads === 1) return new Response(null, { status: 404 })
        return Response.json({
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
          sections: [{ evidenceStatus: 'CITED_DRAFT' }],
        })
      }
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router], stubs: { BackgroundWorkCenter: true } } })
    await flushPromises()

    expect(wrapper.get('[data-testid="pending-guide-journey"] [data-testid="player-work-status"]').text()).toBe('正在组织讲解')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()
    expect(wrapper.get('[data-testid="pending-guide-journey"] [data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(runReads).toBe(1)
    expect(wrapper.text()).not.toContain('立即阅读完整讲解')

    await vi.advanceTimersByTimeAsync(1_500)
    await flushPromises()
    expect(runReads).toBe(2)
    expect(lessonReads).toBe(2)
    expect(wrapper.find('[data-testid="pending-guide-journey"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在补充图片或核对细节')
    expect(wrapper.text()).toContain('立即阅读完整讲解')
    wrapper.unmount()
  })

  it('shows a persisted local-upload handoff before the browser page can start teaching', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/documents/official-imports') return Response.json([])
      if (path === '/api/v1/documents/upload-teaching-handoffs') return Response.json([{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1',
        title: '星际探险', rulebookTitle: 'rules_v4_final.pdf', state: 'WAITING_FOR_DOCUMENT',
        preparationRunId: null, errorCode: null, updatedAt: '2026-08-10T10:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([])
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'rules_v4_final.pdf' },
        latestVersion: { id: 'version-1', status: 'EXTRACTING' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '星际探险' }, editions: [{ id: 'edition-1' }],
      }])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await flushPromises()

    const pending = wrapper.get('[data-testid="pending-guide-journey"]')
    expect(pending.text()).toContain('星际探险')
    expect(pending.text()).toContain('rules_v4_final.pdf')
    expect(pending.get('[data-testid="player-work-status"]').text()).toBe('读取规则书')
    expect(pending.text()).not.toContain('建立检索')
    expect(pending.text()).not.toContain('先读规则书')
    expect(wrapper.text()).toContain('已进入我的讲解')
    wrapper.unmount()
  })

  it('retries a failed local-upload guide through its durable upload handoff', async () => {
    let retried = false
    let retryBody: unknown = null
    let directPreparationStarts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/documents/official-imports') return Response.json([])
      if (path === '/api/v1/documents/upload-teaching-handoffs') return Response.json([{
        id: 'handoff-1', documentVersionId: 'version-1', editionId: 'edition-1',
        title: '星际探险', rulebookTitle: 'rules_v4_final.pdf',
        state: retried ? 'WAITING_FOR_DOCUMENT' : 'LAUNCHED',
        preparationRunId: retried ? null : 'prep-upload-failed', errorCode: null,
        updatedAt: retried ? '2026-08-12T00:01:00Z' : '2026-08-10T10:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([])
      if (path === '/api/v1/assistant-runs/prep-upload-failed') return Response.json({ run: {
        id: 'prep-upload-failed', subjectId: 'version-1', state: 'FAILED',
        lastErrorCode: 'TEACHING_PREPARATION_FAILED', updatedAt: '2026-08-10T10:01:00Z',
      } })
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'rules_v4_final.pdf' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '星际探险' }, editions: [{ id: 'edition-1' }],
      }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/documents/upload-teaching-handoffs/handoff-1/retry' && options?.method === 'POST') {
        retryBody = JSON.parse(String(options.body))
        retried = true
        return Response.json({ id: 'handoff-1', state: 'WAITING_FOR_DOCUMENT', preparationRunId: null }, { status: 202 })
      }
      if (path === '/api/v1/document-versions/version-1/teaching-plans' && options?.method === 'POST') {
        directPreparationStarts += 1
        return Response.json({ assistantRunId: 'duplicate-preparation', state: 'RECEIVED' }, { status: 202 })
      }
      if (path.includes('/api/v1/assistant-runs/active?mode=TEACHING')) return Response.json([])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router], stubs: { BackgroundWorkCenter: true } } })
    await flushPromises()

    const retry = wrapper.get('[data-testid="pending-guide-journey"]')
      .findAll('button').find(button => button.text().includes('重新准备讲解'))
    await retry!.trigger('click')
    await flushPromises()

    expect(retryBody).toEqual({ expectedPreparationRunId: 'prep-upload-failed' })
    expect(directPreparationStarts).toBe(0)
    expect(wrapper.get('[data-testid="pending-guide-journey"] [data-testid="player-work-status"]').text())
      .toBe('读取规则书')
    wrapper.unmount()
  })

  it('shows a persisted preparation failure instead of an endlessly active guide', async () => {
    let retried = false
    let retryBody: unknown = null
    let directPreparationStarts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/documents/official-imports') return Response.json([{
        id: 'import-1', title: '花砖物语', rulebookTitle: 'azul_rules_cn_final.pdf', stage: 'COMPLETED',
        downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1', errorCode: null,
        teachingHandoffState: retried ? 'WAITING_FOR_DOCUMENT' : 'LAUNCHED',
        teachingPreparationRunId: retried ? null : 'prep-1', teachingErrorCode: null,
        updatedAt: retried ? '2026-08-12T00:01:00Z' : '2026-08-10T10:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([])
      if (path === '/api/v1/assistant-runs/prep-1') return Response.json({
        run: {
          id: 'prep-1', subjectId: 'version-1', state: 'FAILED',
          lastErrorCode: 'TEACHING_PREPARATION_FAILED', updatedAt: '2026-08-10T10:01:00Z',
        },
      })
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'azul_rules_cn_final.pdf' }, latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '花砖物语' }, editions: [{ id: 'edition-1' }],
      }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/documents/official-imports/import-1/teaching-retry' && options?.method === 'POST') {
        retryBody = JSON.parse(String(options.body))
        retried = true
        return Response.json({
          id: 'import-1', title: '花砖物语', rulebookTitle: 'azul_rules_cn_final.pdf', stage: 'COMPLETED',
          downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1', errorCode: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null, teachingErrorCode: null,
          updatedAt: '2026-08-12T00:01:00Z',
        }, { status: 202 })
      }
      if (path === '/api/v1/document-versions/version-1/teaching-plans' && options?.method === 'POST') {
        directPreparationStarts += 1
        return Response.json({ assistantRunId: 'duplicate-preparation', state: 'RECEIVED', reused: false }, { status: 202 })
      }
      if (path.includes('/api/v1/assistant-runs/active?mode=TEACHING')) return Response.json([])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { global: { plugins: [router], stubs: { BackgroundWorkCenter: true } } })
    await flushPromises()

    const pending = wrapper.get('[data-testid="pending-guide-journey"]')
    expect(pending.text()).toContain('花砖物语')
    expect(pending.get('[data-testid="player-work-status"]').text()).toBe('需要处理')
    expect(pending.find('.animate-pulse').exists()).toBe(false)
    const retry = pending.findAll('button').find(button => button.text().includes('重新准备讲解'))
    expect(retry).toBeDefined()

    await retry!.trigger('click')
    await flushPromises()

    expect(retryBody).toEqual({ expectedPreparationRunId: 'prep-1' })
    expect(directPreparationStarts).toBe(0)
    const restarted = wrapper.get('[data-testid="pending-guide-journey"]')
    expect(restarted.get('[data-testid="player-work-status"]').text()).toBe('读取规则书')
    expect(restarted.text()).not.toContain('需要处理')
    expect(restarted.find('.animate-pulse').exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens a complete cited draft immediately while detail review continues', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-20T10:02:05Z'))
    let lessonReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'SETI', premise: '寻找外星生命。',
          createdAt: '2026-07-20T10:00:00Z', sections: [{ position: 1, required: true, topicKey: 'setup', title: '完成开局设置', visualEvidenceRecommended: true }],
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest')) {
        return Response.json({
          run: { id: 'run-1', subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
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
          id: 'lesson-1', teachingPlanId: 'plan-1', status: 'DRAFT_READY',
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
    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('正在补充图片或核对细节')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(wrapper.text()).toContain('基础讲解已可用，正在核对第 1 章“完成开局设置”的细节')
    expect(wrapper.text()).toContain('已处理 1/1 节')
    expect(wrapper.text()).not.toMatch(/模型调用|model calls|次内容处理/)
    expect(wrapper.text()).toContain('完整基础讲解已经可读')
    expect(wrapper.text()).toContain('立即阅读完整讲解')
    expect(wrapper.text()).not.toContain('目录已生成')
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('正在补充图片或核对细节')
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
        run: { id: 'run-1', subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:00:00Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-1', subjectId: 'plan-1', state: 'FAILED', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:01:00Z', completedAt: '2026-07-20T10:01:00Z', lastErrorCode: 'MODEL_FAILED' },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-2', subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:02:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
      },
      {
        run: { id: 'run-2', subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:02:00Z', updatedAt: '2026-07-20T10:02:01Z', completedAt: null, lastErrorCode: null },
        budget: { usedModelCalls: 2, maxModelCalls: 144 }, activities: [],
      },
    ]
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'SETI', premise: '寻找外星生命。', createdAt: '2026-07-20T10:00:00Z',
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
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(wrapper.text()).not.toContain('次内容处理')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(wrapper.text()).not.toContain('次内容处理')
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
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: 'SETI', premise: '寻找外星生命。', createdAt: '2026-07-20T10:00:00Z',
          sections: [{ position: 1, required: true, topicKey: 'setup', title: '完成开局设置', visualEvidenceRecommended: true }],
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest')) {
        runReads++
        if (runReads === 2) throw new TypeError('temporary network failure')
        return Response.json({
          run: { id: 'run-1', subjectId: 'plan-1', state: 'RETRIEVING', createdAt: '2026-07-20T10:00:00Z', updatedAt: '2026-07-20T10:02:00Z', completedAt: null, lastErrorCode: null },
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
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(wrapper.text()).not.toContain('次内容处理')
    expect(wrapper.text()).toContain('正在自动重试')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在组织讲解')
    expect(wrapper.text()).not.toContain('次内容处理')
    expect(wrapper.text()).not.toContain('正在自动重试')
    wrapper.unmount()
  })

  it('shows one best continuation per uploaded rulebook until a player asks for history', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') {
        return Response.json([
          {
            id: 'new-plan', documentVersionId: 'version-1', gameTitle: 'Ahoy Rules', premise: '', createdAt: '2026-07-24T10:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
          {
            id: 'readable-plan', documentVersionId: 'version-1', gameTitle: 'Ahoy Rules', premise: '', createdAt: '2026-07-23T10:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
          {
            id: 'pending-plan', documentVersionId: 'version-2', gameTitle: 'Root Rules', premise: '', createdAt: '2026-07-24T11:00:00Z',
            sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: true }],
          },
        ])
      }
      if (path.includes('/api/v1/assistant-runs/latest') && path.includes('readable-plan')) {
        return Response.json({
          run: { id: 'run-1', subjectId: 'readable-plan', state: 'COMPLETED', createdAt: '2026-07-23T10:00:00Z', updatedAt: '2026-07-23T10:01:00Z', completedAt: '2026-07-23T10:01:00Z', lastErrorCode: null },
          budget: { usedModelCalls: 3, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/api/v1/assistant-runs/latest')) return new Response(null, { status: 404 })
      if (path.includes('/illustrated-lessons/latest') && path.includes('readable-plan')) {
        return Response.json({ id: 'lesson-1', teachingPlanId: 'readable-plan', status: 'COMPLETE', sections: [{ evidenceStatus: 'SUPPORTED' }] })
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
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Ahoy Rules').length).toBe(1)
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root Rules').length).toBe(0)
    expect(wrapper.text()).toContain('同一本规则书的 1 个历史版本已收起。')
    expect(wrapper.text()).toContain('讲解已生成')
    expect(wrapper.text()).not.toContain('讲解已经通过规则依据核对')

    const pending = wrapper.findAll('button').find((button) => button.text().includes('待处理 1'))
    expect(pending).toBeDefined()
    await pending!.trigger('click')
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root Rules').length).toBe(1)

    const showAll = wrapper.findAll('button').find((button) => button.text().includes('查看全部 3 个版本'))
    expect(showAll).toBeDefined()
    await showAll!.trigger('click')

    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Ahoy Rules').length).toBe(2)
    expect(wrapper.findAll('h2').filter((heading) => heading.text() === 'Root Rules').length).toBe(1)
    expect(wrapper.text()).toContain('收起历史版本')
    wrapper.unmount()
  })

  it('publishes plan cards before their independent progress reads settle', async () => {
    let releaseRun!: (value: Response) => void
    let releaseLesson!: (value: Response) => void
    const runResponse = new Promise<Response>((resolve) => { releaseRun = resolve })
    const lessonResponse = new Promise<Response>((resolve) => { releaseLesson = resolve })
    const progressSignals: AbortSignal[] = []
    let sessionReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/session') {
        sessionReads += 1
        return Response.json({ username: 'alice', roles: ['USER'] })
      }
      if (path === '/api/v1/teaching-plans') return Response.json([plan('plan-early', '先展示的讲解')])
      if (path.includes('/api/v1/assistant-runs/latest')) {
        progressSignals.push(options?.signal as AbortSignal)
        return runResponse
      }
      if (path.includes('/illustrated-lessons/latest')) {
        progressSignals.push(options?.signal as AbortSignal)
        return lessonResponse
      }
      if (isGuideListPath(path)) return Response.json([])
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })

    await vi.waitFor(() => expect(progressSignals).toHaveLength(2))
    expect(wrapper.text()).toContain('先展示的讲解')
    expect(wrapper.text()).not.toContain('正在加载讲解计划')
    expect(wrapper.find('a[href="/lesson/plan-early"]').exists()).toBe(false)
    expect(sessionReads).toBe(1)

    releaseRun(Response.json({
      run: {
        id: 'run-early', subjectId: 'plan-early', state: 'COMPLETED', createdAt: '2026-08-13T00:00:00Z',
        updatedAt: '2026-08-13T00:01:00Z', completedAt: '2026-08-13T00:01:00Z', lastErrorCode: null,
      },
      budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
    }))
    releaseLesson(Response.json({
      id: 'lesson-early', teachingPlanId: 'plan-early', status: 'COMPLETE',
      sections: [{ evidenceStatus: 'SUPPORTED' }],
    }))
    await vi.waitFor(() => expect(wrapper.find('a[href="/lesson/plan-early"]').exists()).toBe(true))
    wrapper.unmount()
  })

  it('aborts all six list reads on unmount without cancelling durable background work', async () => {
    let releaseLists!: () => void
    const listGate = new Promise<void>((resolve) => { releaseLists = resolve })
    const listSignals: AbortSignal[] = []
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/session') return Response.json({ username: 'alice', roles: ['USER'] })
      if (path === '/api/v1/teaching-plans' || isGuideListPath(path)) {
        listSignals.push(options?.signal as AbortSignal)
        await listGate
        return Response.json(path === '/api/v1/teaching-plans' ? [plan('stale-plan', '晚到的讲解')] : [])
      }
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await vi.waitFor(() => expect(listSignals).toHaveLength(6))

    wrapper.unmount()
    expect(listSignals.every(signal => signal.aborted)).toBe(true)
    releaseLists()
    await flushPromises()

    expect(fetchMock.mock.calls).toHaveLength(7)
    expect(fetchMock.mock.calls.some(([input]) => /cancel|cancellation/i.test(String(input)))).toBe(false)
    expect(fetchMock.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
  })

  it('replaces account progress reads and ignores their late settlement', async () => {
    let releaseOldRun!: (value: Response) => void
    let releaseOldLesson!: (value: Response) => void
    const oldRun = new Promise<Response>((resolve) => { releaseOldRun = resolve })
    const oldLesson = new Promise<Response>((resolve) => { releaseOldLesson = resolve })
    const oldSignals: AbortSignal[] = []
    let planReads = 0
    let sessionReads = 0
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/auth/session') {
        sessionReads += 1
        return Response.json({ username: 'alice', roles: ['USER'] })
      }
      if (path === '/api/v1/teaching-plans') {
        planReads += 1
        return Response.json([plan(planReads === 1 ? 'plan-old' : 'plan-current',
          planReads === 1 ? '旧账户讲解' : '当前账户讲解')])
      }
      if (path.includes('/assistant-runs/latest') && path.includes('plan-old')) {
        oldSignals.push(options?.signal as AbortSignal)
        return oldRun
      }
      if (path.includes('/plan-old/illustrated-lessons/latest')) {
        oldSignals.push(options?.signal as AbortSignal)
        return oldLesson
      }
      if (path.includes('/assistant-runs/latest') && path.includes('plan-current')) {
        return Response.json({
          run: {
            id: 'run-current', subjectId: 'plan-current', state: 'COMPLETED', createdAt: '2026-08-13T00:00:00Z',
            updatedAt: '2026-08-13T00:01:00Z', completedAt: '2026-08-13T00:01:00Z', lastErrorCode: null,
          }, budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/plan-current/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-current', teachingPlanId: 'plan-current', status: 'COMPLETE', sections: [],
        })
      }
      if (isGuideListPath(path)) return Response.json([])
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await vi.waitFor(() => expect(oldSignals).toHaveLength(2))
    expect(wrapper.text()).toContain('旧账户讲解')

    window.dispatchEvent(new Event(LOGIN_REQUIRED_EVENT))
    await vi.waitFor(() => expect(wrapper.text()).toContain('当前账户讲解'))
    expect(oldSignals.every(signal => signal.aborted)).toBe(true)
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('需要处理')
    expect(wrapper.text()).not.toContain('讲解完成')
    expect(wrapper.text()).not.toContain('旧账户讲解')
    expect(sessionReads).toBe(1)

    releaseOldRun(Response.json({
      run: {
        id: 'run-old', subjectId: 'plan-old', state: 'FAILED', createdAt: '2026-08-13T00:00:00Z',
        updatedAt: '2026-08-13T00:01:00Z', completedAt: '2026-08-13T00:01:00Z', lastErrorCode: 'STALE',
      }, budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
    }))
    releaseOldLesson(Response.json({
      id: 'lesson-old', teachingPlanId: 'plan-old', status: 'COMPLETE', sections: [],
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('当前账户讲解')
    expect(wrapper.text()).not.toContain('旧账户讲解')
    expect(wrapper.text()).not.toContain('正在自动重试')
    expect(fetchMock.mock.calls.some(([input]) => /cancel|cancellation/i.test(String(input)))).toBe(false)
    wrapper.unmount()
  })

  it('rejects mismatched known-run and lesson identities before merging progress', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path === '/api/auth/session') return Response.json({ username: 'alice', roles: ['USER'] })
      if (path === '/api/v1/teaching-plans') {
        return Response.json([
          plan('plan-run-mismatch', '错误任务对象'),
          plan('plan-lesson-mismatch', '错误讲解对象'),
        ])
      }
      if (path === '/api/v1/assistant-runs/run-expected') {
        return Response.json({
          run: {
            id: 'run-other', subjectId: 'plan-run-mismatch', state: 'COMPLETED', createdAt: '2026-08-13T00:00:00Z',
            updatedAt: '2026-08-13T00:01:00Z', completedAt: '2026-08-13T00:01:00Z', lastErrorCode: null,
          }, budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/assistant-runs/latest') && path.includes('plan-lesson-mismatch')) {
        return Response.json({
          run: {
            id: 'run-lesson', subjectId: 'plan-lesson-mismatch', state: 'COMPLETED', createdAt: '2026-08-13T00:00:00Z',
            updatedAt: '2026-08-13T00:01:00Z', completedAt: '2026-08-13T00:01:00Z', lastErrorCode: null,
          }, budget: { usedModelCalls: 1, maxModelCalls: 144 }, activities: [],
        })
      }
      if (path.includes('/plan-run-mismatch/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-run', teachingPlanId: 'plan-run-mismatch', status: 'COMPLETE', sections: [],
        })
      }
      if (path.includes('/plan-lesson-mismatch/illustrated-lessons/latest')) {
        return Response.json({
          id: 'lesson-other', teachingPlanId: 'plan-other', status: 'COMPLETE', sections: [],
        })
      }
      if (isGuideListPath(path)) return Response.json([])
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons?started=plan-run-mismatch&run=run-expected')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })

    await vi.waitFor(() => expect(
      (wrapper.text().match(/暂时没拿到最新进度/g) ?? []).length,
    ).toBe(2))
    expect(wrapper.find('a[href="/lesson/plan-run-mismatch"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/lesson/plan-lesson-mismatch"]').exists()).toBe(false)
    expect(wrapper.findAll('button').filter(button => button.text() === '开始生成')).toHaveLength(2)
    wrapper.unmount()
  })

  it('previews duplicate cleanup before confirmation and keeps the dialog retryable after failure', async () => {
    let cleanupAttempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([
        plan('plan-1', 'Root'), plan('plan-2', 'Root'),
      ])
      if (path === '/api/v1/teaching-plans/cleanup-preview') return Response.json({ duplicateCount: 1 })
      if (path === '/api/v1/teaching-plans/cleanup-duplicates' && options?.method === 'POST') {
        cleanupAttempts += 1
        return cleanupAttempts === 1
          ? new Response(null, { status: 503 })
          : Response.json({ deletedCount: 1 })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/assistant-runs/latest') || path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path === '/api/v1/documents/official-imports' || path === '/api/v1/documents/upload-teaching-handoffs'
        || path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION' || path === '/api/v1/documents'
        || path === '/api/v1/games') return Response.json([])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text().includes('整理重复讲解'))!.trigger('click')
    await flushPromises()
    expect(cleanupAttempts).toBe(0)
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('发现 1 份重复讲解')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('清理重复项'))!.click()
    await flushPromises()
    expect(cleanupAttempts).toBe(1)
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('重复讲解暂时无法清理')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('重新尝试清理'))!.click()
    await flushPromises()
    expect(cleanupAttempts).toBe(2)
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.text()).toContain('已清理 1 份重复讲解')
    expect(document.activeElement).toBe(wrapper.get('h1').element)
    wrapper.unmount()
  })

  it('deletes one guide only after confirmation and leaves a failed target available for retry', async () => {
    let deleteAttempts = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([plan('plan-1', 'Root')])
      if (path === '/api/v1/teaching-plans/plan-1' && options?.method === 'DELETE') {
        deleteAttempts += 1
        return new Response(null, { status: deleteAttempts === 1 ? 503 : 204 })
      }
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.includes('/api/v1/assistant-runs/latest') || path.includes('/illustrated-lessons/latest')) return new Response(null, { status: 404 })
      if (path === '/api/v1/documents/official-imports' || path === '/api/v1/documents/upload-teaching-handoffs'
        || path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION' || path === '/api/v1/documents'
        || path === '/api/v1/games') return Response.json([])
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '删除')!.trigger('click')
    await flushPromises()
    expect(deleteAttempts).toBe(0)
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('Root')
    expect(document.activeElement?.textContent).toContain('保留讲解')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('删除讲解'))!.click()
    await flushPromises()
    expect(deleteAttempts).toBe(1)
    expect(wrapper.text()).toContain('Root')
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('讲解暂时无法删除')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('重新尝试删除'))!.click()
    await flushPromises()
    expect(deleteAttempts).toBe(2)
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.text()).not.toContain('Root')
    expect(wrapper.text()).toContain('讲解已删除')
    expect(document.activeElement).toBe(wrapper.get('h1').element)
    wrapper.unmount()
  })

  it('persistently deletes a failed preparation attempt while keeping its rulebook', async () => {
    const deletePaths: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/teaching-plans') return Response.json([])
      if (path === '/api/v1/documents/official-imports') return Response.json([])
      if (path === '/api/v1/documents/upload-teaching-handoffs') return Response.json([{
        id: 'handoff-failed', documentVersionId: 'version-1', editionId: 'edition-1',
        title: '星际探险', rulebookTitle: 'rules_v4_final.pdf', state: 'LAUNCHED',
        preparationRunId: 'prep-failed', errorCode: null, updatedAt: '2026-08-16T00:00:00Z',
      }])
      if (path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION') return Response.json([])
      if (path === '/api/v1/assistant-runs/prep-failed') return Response.json({ run: {
        id: 'prep-failed', subjectId: 'version-1', state: 'FAILED',
        lastErrorCode: 'TEACHING_PREPARATION_FAILED', updatedAt: '2026-08-16T00:01:00Z',
      } })
      if (path === '/api/v1/documents') return Response.json([{
        document: { gameEditionId: 'edition-1', title: 'rules_v4_final.pdf' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path === '/api/v1/games') return Response.json([{
        game: { name: '星际探险' }, editions: [{ id: 'edition-1' }],
      }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (options?.method === 'DELETE') {
        deletePaths.push(path)
        return new Response(null, { status: 204 })
      }
      if (path.includes('/api/auth/session')) return Response.json({ username: 'alice', roles: ['USER'] })
      return new Response(null, { status: 404 })
    }))
    const router = createMemoryRouter()
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(LessonsView, {
      attachTo: document.body,
      global: { plugins: [router], stubs: { BackgroundWorkCenter: true } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="pending-guide-journey"]').text()).toContain('rules_v4_final.pdf')
    await wrapper.get('[data-testid="delete-failed-guide-attempt"]').trigger('click')
    await flushPromises()
    expect(deletePaths).toEqual([])
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('规则书会保留')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('删除失败记录'))!.click()
    await flushPromises()

    expect(deletePaths).toEqual(['/api/v1/teaching-preparation-failures/uploads/handoff-failed'])
    expect(wrapper.find('[data-testid="pending-guide-journey"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('失败的讲解准备记录已删除，规则书仍然保留')
    wrapper.unmount()
  })
})

function plan(id: string, gameTitle: string) {
  return {
    id, documentVersionId: `version-${id}`, gameTitle, premise: '', createdAt: '2026-08-13T00:00:00Z',
    sections: [{ position: 1, required: true, topicKey: 'setup', title: '设置', visualEvidenceRecommended: false }],
  }
}

function isGuideListPath(path: string) {
  return path === '/api/v1/documents/official-imports'
    || path === '/api/v1/documents/upload-teaching-handoffs'
    || path === '/api/v1/assistant-runs/active?mode=TEACHING_PREPARATION'
    || path === '/api/v1/documents'
    || path === '/api/v1/games'
}

function createMemoryRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: LessonsView },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
      { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/settings/models', name: 'model-settings', component: { template: '<div />' } },
    ],
  })
}
