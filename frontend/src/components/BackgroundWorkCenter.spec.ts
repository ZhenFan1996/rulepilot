import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { backgroundWorkStorageKeys } from '@/lib/backgroundTeachingStatus'
import { notifyBackgroundWorkChanged } from '@/lib/backgroundWorkRefresh'
import { preloadLocale, setLocale } from '@/lib/locale'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import BackgroundWorkCenter from './BackgroundWorkCenter.vue'

enableAutoUnmount(afterEach)

describe('BackgroundWorkCenter request lifecycle', () => {
  afterEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    setLocale('zh-CN')
    setVisibility('visible')
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('aborts an older refresh and never lets its late bundle replace newly launched work', async () => {
    let activeReads = 0
    let resolveFirstActive!: () => void
    let firstSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        if (activeReads === 1) {
          firstSignal = init?.signal ?? undefined
          return new Promise<Response>((resolve) => {
            resolveFirstActive = () => resolve(response([
              teachingRun('run-old', 'plan-old', 'player', 'LESSON_COMPOSITION'),
            ]))
          })
        }
        return Promise.resolve(response([
          teachingRun('run-new', 'plan-new', 'player', 'RECEIVED'),
        ]))
      }
      if (path.endsWith('/api/v1/teaching-plans')) return Promise.resolve(response([
        { id: 'plan-old', gameTitle: '旧讲解' },
        { id: 'plan-new', gameTitle: '新讲解' },
      ]))
      if (path.endsWith('/api/v1/assistant-runs/run-new')) {
        return Promise.resolve(response({ run: teachingRun('run-new', 'plan-new', 'player', 'RECEIVED') }))
      }
      if (isBackgroundBaseList(path)) return Promise.resolve(response([]))
      return Promise.resolve(new Response(null, { status: 404 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountCenter('player')
    await flushPromises()
    expect(activeReads).toBe(1)

    notifyTeachingLaunched({ planId: 'plan-new', runId: 'run-new', gameTitle: '新讲解' })
    await flushPromises()
    await openCenter(wrapper)

    expect(firstSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('新讲解')
    expect(wrapper.text()).not.toContain('旧讲解')
    expect(sessionStorage.getItem(backgroundWorkStorageKeys('player').activeTeaching)).toContain('run-new')

    resolveFirstActive()
    await flushPromises()

    expect(wrapper.text()).toContain('新讲解')
    expect(wrapper.text()).not.toContain('旧讲解')
    expect(sessionStorage.getItem(backgroundWorkStorageKeys('player').activeTeaching)).not.toContain('run-old')
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
    wrapper.unmount()
  })

  it('refreshes immediately when durable background work changes instead of waiting for the idle timer', async () => {
    vi.useFakeTimers()
    let activeReads = 0
    let importReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        return response([])
      }
      if (path.endsWith('/api/v1/documents/official-imports')) {
        importReads += 1
        return response(importReads === 1 ? [] : [{
          id: 'import-immediate', title: '刚保存的讲解', sourceDomain: 'publisher.example', stage: 'QUEUED',
          downloadedBytes: 0, totalBytes: null, documentVersionId: null, errorCode: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
          teachingErrorCode: null, downloadCompletedAt: null, importCompletedAt: null,
          teachingHandoffUpdatedAt: null, updatedAt: '2026-08-14T05:00:00Z',
        }])
      }
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')
        || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.text()).toContain('当前没有后台任务')
    expect(activeReads).toBe(1)
    expect(importReads).toBe(1)

    notifyBackgroundWorkChanged()
    await flushPromises()

    expect(activeReads).toBe(2)
    expect(importReads).toBe(2)
    expect(wrapper.text()).toContain('刚保存的讲解')
    expect(wrapper.text()).toContain('等待下载')
    wrapper.unmount()
  })

  it('publishes a validated import immediately while the durable refresh is still pending', async () => {
    let initialRefresh = true
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const path = String(input)
      if (initialRefresh && (path.includes('/api/v1/assistant-runs/active') || isBackgroundBaseList(path))) {
        return Promise.resolve(response([]))
      }
      return new Promise<Response>(() => {})
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.text()).toContain('当前没有后台任务')

    initialRefresh = false
    notifyBackgroundWorkChanged({
      importJob: {
        id: 'import-optimistic', title: '刚开始的里斯本讲解', sourceDomain: 'publisher.example', stage: 'QUEUED',
        downloadedBytes: 0, totalBytes: null, documentVersionId: null, errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        teachingErrorCode: null, downloadCompletedAt: null, importCompletedAt: null,
        teachingHandoffUpdatedAt: null, updatedAt: '2026-08-23T06:00:00Z',
      },
    })
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('刚开始的里斯本讲解')
    expect(wrapper.text()).toContain('等待下载')
    wrapper.unmount()
  })

  it('keeps the missing-result reason and bounded recovery visible after the launch dialog is closed', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-recovering', title: '关闭弹窗后仍可追踪', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-recovering', errorCode: null,
        teachingHandoffState: 'WAITING_FOR_DOCUMENT', teachingPreparationRunId: null,
        teachingErrorCode: null, teachingAutomaticRecoveryCount: 1,
        downloadCompletedAt: '2026-08-20T06:00:00Z', importCompletedAt: '2026-08-20T06:00:00Z',
        teachingHandoffUpdatedAt: '2026-08-20T06:00:05Z', updatedAt: '2026-08-20T06:00:05Z',
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-recovering', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-recovering', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('关闭弹窗后仍可追踪')
    expect(wrapper.text()).toContain('上一次任务没有留下可读章节，正在进行第 1 / 1 次自动恢复')
    wrapper.unmount()
  })

  it('stops claiming progress when the single automatic recovery still produced no readable chapter', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-recovery-exhausted', title: '需要人工重试的讲解', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096,
        documentVersionId: 'version-recovery-exhausted', errorCode: null,
        teachingHandoffState: 'FAILED', teachingPreparationRunId: null,
        teachingErrorCode: 'TEACHING_RECOVERY_EXHAUSTED', teachingAutomaticRecoveryCount: 1,
        downloadCompletedAt: '2026-08-20T06:00:00Z', importCompletedAt: '2026-08-20T06:00:00Z',
        teachingHandoffUpdatedAt: '2026-08-20T06:00:10Z', updatedAt: '2026-08-20T06:00:10Z',
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-recovery-exhausted', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-recovery-exhausted', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('自动恢复后仍没有生成可读章节')
    expect(wrapper.text()).toContain('已完成第 1 / 1 次自动恢复；请打开讲解中心重试')
    expect(wrapper.text()).not.toContain('正在自动恢复')
    wrapper.unmount()
  })

  it('shows the latest real chapter operation and persisted publication count after the launch dialog is gone', async () => {
    const activities = [
      teachingActivity(1, 'publishTeachingSection|1', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      teachingActivity(2, 'publishTeachingSection|2', 'SUCCEEDED', 'Teaching section published: CITED_BASE_SECTION_PUBLISHED'),
      teachingActivity(3, 'composeTeachingSection|3', 'RUNNING', 'Writing the next section'),
    ]
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        return response([teachingRun('run-live', 'plan-live', 'player', 'RETRIEVING')])
      }
      if (path.endsWith('/api/v1/assistant-runs/run-live')) {
        return response({
          run: {
            ...teachingRun('run-live', 'plan-live', 'player', 'RETRIEVING'),
            createdAt: '2026-08-20T06:00:00Z', updatedAt: '2026-08-20T06:00:03Z',
            completedAt: null, lastErrorCode: null,
          },
          budget: { usedModelCalls: 3, maxModelCalls: 36 },
          activities,
        })
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        return response([{
          id: 'plan-live', gameTitle: '持续可追踪的讲解',
          sections: Array.from({ length: 5 }, (_, index) => ({
            position: index + 1, title: `章节 ${index + 1}`, visualEvidenceRecommended: false,
          })),
        }])
      }
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('正在依据规则书编写第 3 章“章节 3”')
    expect(wrapper.text()).toContain('已发布 2 / 5 章')
    wrapper.unmount()
  })

  it('shows the real visual-rulebook page while preparation is still planning chapters', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-visual', title: '图像规则讲解', sourceDomain: 'publisher.example', stage: 'COMPLETED',
        downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-visual', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'prep-visual', teachingErrorCode: null,
        downloadCompletedAt: '2026-08-20T06:00:00Z', importCompletedAt: '2026-08-20T06:00:00Z',
        teachingHandoffUpdatedAt: '2026-08-20T06:00:01Z', updatedAt: '2026-08-20T06:00:01Z',
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-visual', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-visual', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/assistant-runs/prep-visual')) return response({
        run: {
          ...preparationRun('prep-visual', 'version-visual', 'player', 'LESSON_PLANNING'),
          createdAt: '2026-08-20T06:00:01Z', updatedAt: '2026-08-20T06:00:04Z',
          completedAt: null, lastErrorCode: null,
        },
        budget: { usedModelCalls: 3, maxModelCalls: 36 },
        activities: [teachingActivity(
          5, 'inspectTeachingVisualPage|3|12', 'RUNNING', 'Inspecting visual page three',
        )],
      })
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('正在整理图像规则页第 3 / 12 页的规则组')
    wrapper.unmount()
  })

  it('removes a dismissed failed preparation from background work while retaining its official import', async () => {
    let dismissed = false
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-failed-preparation', title: '仍然保留的规则书', sourceDomain: 'publisher.example',
        stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-kept',
        errorCode: null, teachingHandoffState: dismissed ? 'NOT_REQUESTED' : 'FAILED',
        teachingPreparationRunId: null, teachingErrorCode: dismissed ? null : 'TEACHING_PREPARATION_FAILED',
        downloadCompletedAt: '2026-08-16T08:00:00Z', importCompletedAt: '2026-08-16T08:00:00Z',
        teachingHandoffUpdatedAt: dismissed ? null : '2026-08-16T08:01:00Z',
        updatedAt: new Date().toISOString(),
      }])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-kept', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-kept', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.text()).toContain('仍然保留的规则书')
    expect(wrapper.text()).toContain('需要处理')

    dismissed = true
    notifyBackgroundWorkChanged({ dismissedImportIds: ['import-failed-preparation'] })
    await flushPromises()

    expect(wrapper.text()).not.toContain('仍然保留的规则书')
    expect(wrapper.text()).toContain('当前没有后台任务')
    expect(sessionStorage.getItem(backgroundWorkStorageKeys('player').dismissedImports))
      .toContain('import-failed-preparation')

    notifyBackgroundWorkChanged({
      dismissedImportIds: Array.from({ length: 101 }, (_, index) => `older-failed-import-${index}`),
    })
    await flushPromises()
    expect(JSON.parse(sessionStorage.getItem(backgroundWorkStorageKeys('player').dismissedImports) ?? '[]'))
      .toHaveLength(102)
    wrapper.unmount()
  })

  it('durably clears a failed persisted item so a stale server snapshot cannot resurrect it after remount', async () => {
    const failedImport = {
      id: 'import-stale-after-clear', title: '已清除的失败讲解', sourceDomain: 'publisher.example',
      stage: 'COMPLETED', downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-stale',
      errorCode: null, teachingHandoffState: 'FAILED', teachingPreparationRunId: null,
      teachingErrorCode: 'TEACHING_PREPARATION_FAILED', downloadCompletedAt: '2026-08-16T08:00:00Z',
      importCompletedAt: '2026-08-16T08:00:00Z', teachingHandoffUpdatedAt: '2026-08-16T08:01:00Z',
      updatedAt: new Date().toISOString(),
    }
    const requests: Array<{ path: string; method: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      const method = init?.method ?? 'GET'
      requests.push({ path, method })
      if (path === '/api/auth/csrf') {
        return response({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      }
      if (path === '/api/v1/teaching-preparation-failures/official-imports/import-stale-after-clear'
        && method === 'DELETE') return new Response(null, { status: 204 })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([failedImport])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-stale', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-stale', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      return new Response(null, { status: 404 })
    }))

    const first = await mountCenter('player')
    await flushPromises()
    await openCenter(first)
    expect(first.text()).toContain('已清除的失败讲解')

    await first.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()

    expect(requests).toContainEqual({
      path: '/api/v1/teaching-preparation-failures/official-imports/import-stale-after-clear',
      method: 'DELETE',
    })
    expect(first.text()).not.toContain('已清除的失败讲解')
    const dismissedKey = backgroundWorkStorageKeys('player').dismissedImports
    expect(JSON.parse(localStorage.getItem(dismissedKey) ?? '[]'))
      .toContain('import-stale-after-clear')
    first.unmount()

    const remounted = await mountCenter('player')
    await flushPromises()
    await openCenter(remounted)

    expect(remounted.text()).not.toContain('已清除的失败讲解')
    expect(remounted.text()).toContain('当前没有后台任务')
    remounted.unmount()
  })

  it.each([
    ['RECEIVED', '正在等待讲解 worker'],
    ['DOCUMENT_READINESS', '正在确认规则书可以用于讲解'],
    ['LESSON_PLANNING', '正在整理讲解结构'],
    ['RETRIEVAL_PLANNING', '正在确定各章节需要核对的规则'],
    ['RETRIEVING', '正在查找各章节需要的规则依据'],
    ['VERIFYING_EVIDENCE', '正在逐条核对讲解与规则依据'],
    ['LESSON_COMPOSITION', '正在把规则整理成可读的讲解'],
    ['MEDIA_PACKAGING', '正在补充规则页与图示'],
    ['CRITIQUING', '正在复核讲解中的规则结论'],
  ])('translates the active Teaching state %s instead of exposing its internal enum', async (state, expected) => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        return response([teachingRun('run-progress', 'plan-progress', 'player', state)])
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-progress', gameTitle: '真实玩家讲解' }])
      }
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain(expected)
    expect(wrapper.text()).not.toContain(state)
    wrapper.unmount()
  })

  it('localizes Teaching progress in English without exposing the internal enum', async () => {
    await preloadLocale('en')
    setLocale('en')
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        return response([teachingRun('run-review', 'plan-review', 'player', 'CRITIQUING')])
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-review', gameTitle: 'A real player guide' }])
      }
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain("Reviewing the guide's rule claims")
    expect(wrapper.text()).not.toContain('CRITIQUING')
    wrapper.unmount()
  })

  it('presents rulebook reading with the shared player status and hides processing internals', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-1', title: 'player-rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'EMBEDDING' },
      }])
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.includes('/document-versions/version-1/progress/snapshot')) return response({
        stage: 'EMBEDDING', percentage: 72, processedPages: 8, totalPages: 12, complete: false,
      })
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('读取规则书')
    expect(status.attributes('data-player-work-capability')).toBe('none')
    expect(status.attributes('data-player-work-readiness')).toBe('unavailable')
    expect(wrapper.text()).not.toMatch(/语义索引|可检索规则段落|EMBEDDING/)
    wrapper.unmount()
  })

  it('does not let an aborted preparation bridge publish its plan relationship into the replacement refresh', async () => {
    let planReads = 0
    let staleLatestReads = 0
    let staleSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([{
        id: 'import-cache-race', title: '缓存竞态讲解', sourceDomain: 'publisher.example', stage: 'COMPLETED',
        downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-cache', errorCode: null,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-cache', teachingErrorCode: null,
        downloadCompletedAt: '2026-08-14T05:00:00Z', importCompletedAt: '2026-08-14T05:00:00Z',
        teachingHandoffUpdatedAt: '2026-08-14T05:00:00Z', updatedAt: '2026-08-14T05:00:00Z',
      }])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-cache', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-cache', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-cache')) {
        return response({ run: preparationRun('preparation-cache', 'version-cache', 'player', 'COMPLETED') })
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        planReads += 1
        return response([{
          id: planReads === 1 ? 'plan-stale' : 'plan-current', documentVersionId: 'version-cache',
          gameTitle: planReads === 1 ? '旧关系' : '当前关系', createdAt: `2026-08-14T05:00:0${planReads}Z`,
        }])
      }
      if (path.includes('subjectId=plan-stale')) {
        staleLatestReads += 1
        if (staleLatestReads > 1) {
          return response({ run: teachingRun('teaching-stale', 'plan-stale', 'player', 'LESSON_COMPOSITION') })
        }
        staleSignal = init?.signal ?? undefined
        return await new Promise<Response>((_resolve, reject) => {
          staleSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
        })
      }
      if (path.includes('subjectId=plan-current')) {
        return response({ run: teachingRun('teaching-current', 'plan-current', 'player', 'LESSON_COMPOSITION') })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    expect(planReads).toBe(1)

    notifyBackgroundWorkChanged()
    await flushPromises()
    await openCenter(wrapper)

    expect(staleSignal?.aborted).toBe(true)
    expect(planReads).toBe(2)
    expect(wrapper.text()).toContain('当前关系')
    expect(wrapper.text()).not.toContain('旧关系')
    wrapper.unmount()
  })

  it('bridges completed preparation into its exact Teaching run without an empty idle interval', async () => {
    vi.useFakeTimers()
    let refreshes = 0
    let latestTeachingReads = 0
    let planReads = 0
    sessionStorage.setItem(backgroundWorkStorageKeys('player').completedTeaching, JSON.stringify([{
      runId: 'run-finished', planId: 'plan-finished', gameTitle: '已结束的旧讲解', terminalState: 'COMPLETED',
    }]))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) {
        refreshes += 1
        return response([{
          id: 'import-transition', title: '过渡中的讲解', sourceDomain: 'publisher.example', stage: 'COMPLETED',
          downloadedBytes: 4096, totalBytes: 4096, documentVersionId: 'version-1', errorCode: null,
          teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: 'preparation-1', teachingErrorCode: null,
          downloadCompletedAt: '2026-08-14T05:00:00Z', importCompletedAt: '2026-08-14T05:00:00Z',
          teachingHandoffUpdatedAt: '2026-08-14T05:00:00Z', updatedAt: '2026-08-14T05:00:00Z',
        }])
      }
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-1')) {
        return response({
          run: preparationRun(
            'preparation-1', 'version-1', 'player', refreshes === 1 ? 'LESSON_PLANNING' : 'COMPLETED',
          ),
        })
      }
      if (path.endsWith('/api/v1/teaching-plans')) {
        planReads += 1
        return response([{
          id: 'plan-1', documentVersionId: 'version-1', gameTitle: '过渡中的讲解',
          createdAt: '2026-08-14T05:00:01Z',
        }])
      }
      if (path.includes('/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=plan-1')) {
        latestTeachingReads += 1
        if (latestTeachingReads === 1) return new Response(null, { status: 404 })
        return response({
          run: teachingRun('teaching-1', 'plan-1', 'player', 'LESSON_COMPOSITION'),
        })
      }
      if (path.endsWith('/api/v1/assistant-runs/teaching-1')) {
        return response({ run: teachingRun('teaching-1', 'plan-1', 'player', 'LESSON_COMPOSITION') })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.text()).toContain('正在整理讲解结构')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(latestTeachingReads).toBe(1)
    expect(wrapper.text()).toContain('过渡中的讲解')
    expect(wrapper.text()).toContain('规则书已就绪，正在启动讲解任务')
    expect(wrapper.text()).not.toContain('当前没有后台任务')

    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('规则书已就绪，正在启动讲解任务')
    expect(wrapper.text()).not.toContain('已结束的旧讲解')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(latestTeachingReads).toBe(2)
    expect(wrapper.text()).toContain('正在组织讲解')
    expect(wrapper.text()).not.toContain('当前没有后台任务')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()
    expect(planReads).toBe(1)
    wrapper.unmount()
  })

  it('reports a verified failed Teaching run as needing attention instead of complete', async () => {
    const keys = backgroundWorkStorageKeys('player')
    sessionStorage.setItem(keys.activeTeaching, JSON.stringify([{
      runId: 'run-failed', planId: 'plan-failed', gameTitle: '失败的讲解',
    }]))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/assistant-runs/run-failed')) {
        return response({
          run: {
            ...teachingRun('run-failed', 'plan-failed', 'player', 'INSUFFICIENT_EVIDENCE'),
            createdAt: '2026-08-20T06:00:00Z', updatedAt: '2026-08-20T06:00:08Z',
            completedAt: '2026-08-20T06:00:08Z', lastErrorCode: null,
          },
          budget: { usedModelCalls: 0, maxModelCalls: 36 },
          activities: Array.from({ length: 8 }, (_, index) => teachingActivity(
            index + 1,
            `publishTeachingSection|${index + 1}`,
            'REJECTED',
            'Teaching section withheld: NO_VALID_BASE_EVIDENCE',
          )),
        })
      }
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('失败的讲解')
    expect(wrapper.text()).toContain('需要处理')
    expect(wrapper.text()).toContain('已处理 8 章')
    expect(wrapper.text()).toContain('8 章未发布')
    expect(wrapper.text()).toContain('引用页没有形成可供这些章节发布的规则依据')
    expect(wrapper.text()).not.toContain('已完成')
    expect(sessionStorage.getItem(keys.completedTeaching)).toContain('"terminalState":"INSUFFICIENT_EVIDENCE"')
    wrapper.unmount()
  })

  it('does not resurrect a durably dismissed Teaching result from a stale tab snapshot', async () => {
    const keys = backgroundWorkStorageKeys('player')
    const finished = {
      runId: 'run-cleared', planId: 'plan-cleared', gameTitle: '已经清除的讲解', terminalState: 'FAILED',
    }
    sessionStorage.setItem(keys.completedTeaching, JSON.stringify([finished]))
    localStorage.setItem(keys.dismissedTeachingRuns, JSON.stringify(['run-cleared']))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))

    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('当前没有后台任务')
    expect(wrapper.text()).not.toContain('已经清除的讲解')
    expect(sessionStorage.getItem(keys.completedTeaching)).toBe('[]')
    wrapper.unmount()
  })

  it('retains monotonic document progress through a failed snapshot and a lower recovery snapshot', async () => {
    vi.useFakeTimers()
    let snapshotReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-1', title: '慢速规则书.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'EXTRACTING' },
      }])
      if (path.endsWith('/api/v1/documents/official-imports')
        || path.endsWith('/api/v1/documents/upload-teaching-handoffs')) return response([])
      if (path.includes('/document-versions/version-1/progress/snapshot')) {
        snapshotReads += 1
        if (snapshotReads === 2) return new Response(null, { status: 503 })
        return response({
          stage: 'EXTRACTING', percentage: snapshotReads === 1 ? 35 : 20,
          processedPages: snapshotReads === 1 ? 3 : 2, totalPages: 12, complete: false,
        })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.get('[aria-label="35%"]')).toBeTruthy()

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(snapshotReads).toBe(2)
    expect(wrapper.get('[aria-label="35%"]')).toBeTruthy()
    expect(wrapper.text()).toContain('暂时没有拿到最新进度')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(snapshotReads).toBe(3)
    expect(wrapper.get('[aria-label="35%"]')).toBeTruthy()
    expect(wrapper.text()).not.toContain('暂时没有拿到最新进度')
    wrapper.unmount()
  })

  it('replaces account state immediately and rejects a late response from the previous account', async () => {
    const playerKeys = backgroundWorkStorageKeys('player')
    const otherKeys = backgroundWorkStorageKeys('other')
    sessionStorage.setItem(playerKeys.activeTeaching, JSON.stringify([
      { runId: 'run-player', planId: 'plan-player', gameTitle: '玩家一的讲解' },
    ]))
    sessionStorage.setItem(otherKeys.activeTeaching, JSON.stringify([
      { runId: 'run-other', planId: 'plan-other', gameTitle: '玩家二的讲解' },
    ]))
    let activeReads = 0
    let resolvePlayerActive!: () => void
    let playerSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        if (activeReads === 1) {
          playerSignal = init?.signal ?? undefined
          return new Promise<Response>((resolve) => {
            resolvePlayerActive = () => resolve(response([
              teachingRun('run-player', 'plan-player', 'player', 'LESSON_COMPOSITION'),
            ]))
          })
        }
        return Promise.resolve(response([]))
      }
      if (path.endsWith('/api/v1/assistant-runs/run-other')) {
        return Promise.resolve(response({ run: teachingRun('run-other', 'plan-other', 'other', 'RECEIVED') }))
      }
      if (isBackgroundBaseList(path)) return Promise.resolve(response([]))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()

    await wrapper.setProps({ username: 'other' })
    await flushPromises()
    await openCenter(wrapper)

    expect(playerSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('玩家二的讲解')
    expect(wrapper.text()).not.toContain('玩家一的讲解')

    resolvePlayerActive()
    await flushPromises()

    expect(wrapper.text()).toContain('玩家二的讲解')
    expect(wrapper.text()).not.toContain('玩家一的讲解')
    expect(sessionStorage.getItem(playerKeys.activeTeaching)).toContain('run-player')
    expect(sessionStorage.getItem(otherKeys.activeTeaching)).toContain('run-other')
    wrapper.unmount()
  })

  it.each([
    ['subject', teachingRun('run-1', 'wrong-plan', 'player', 'COMPLETED')],
    ['owner', teachingRun('run-1', 'plan-1', 'intruder', 'COMPLETED')],
  ])('retains the last truthful run when an exact response has the wrong %s identity', async (_field, mismatchedRun) => {
    const keys = backgroundWorkStorageKeys('player')
    sessionStorage.setItem(keys.activeTeaching, JSON.stringify([
      { runId: 'run-1', planId: 'plan-1', gameTitle: '可信讲解' },
    ]))
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/assistant-runs/run-1')) {
        return response({ run: mismatchedRun })
      }
      if (isBackgroundBaseList(path)) return response([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)

    expect(wrapper.text()).toContain('可信讲解')
    expect(wrapper.text()).toContain('正在组织讲解')
    expect(wrapper.text()).not.toContain('已完成')
    expect(wrapper.text()).toContain('暂时没有拿到最新进度')
    expect(sessionStorage.getItem(keys.activeTeaching)).toContain('run-1')
    wrapper.unmount()
  })

  it('keeps the coherent prior bundle when a handoff references an unknown document version', async () => {
    vi.useFakeTimers()
    let handoffReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) {
        handoffReads += 1
        return response([{
          id: 'handoff-1', documentVersionId: handoffReads === 1 ? 'version-1' : 'unknown-version',
          title: handoffReads === 1 ? '可信的绑定讲解' : '不应发布的讲解', rulebookTitle: 'rules.pdf',
          state: 'WAITING_FOR_DOCUMENT', preparationRunId: null, errorCode: null,
          updatedAt: '2026-08-13T00:00:00Z',
        }])
      }
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: 'version-1', status: 'READY' },
      }])
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    await openCenter(wrapper)
    expect(wrapper.text()).toContain('可信的绑定讲解')

    await vi.advanceTimersByTimeAsync(4_000)
    await flushPromises()

    expect(handoffReads).toBe(2)
    expect(wrapper.text()).toContain('可信的绑定讲解')
    expect(wrapper.text()).not.toContain('不应发布的讲解')
    expect(wrapper.text()).toContain('暂时没有拿到最新进度')
    wrapper.unmount()
  })

  it('does not reuse a terminal preparation state after the same run id moves to another version', async () => {
    vi.useFakeTimers()
    let preparationReads = 0
    let refreshes = 0
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.endsWith('/api/v1/documents/official-imports')) return response([])
      if (path.endsWith('/api/v1/documents/upload-teaching-handoffs')) {
        refreshes += 1
        return response([{
          id: 'handoff-versioned', documentVersionId: refreshes === 1 ? 'version-1' : 'version-2',
          title: '版本绑定讲解', rulebookTitle: 'rules.pdf', state: 'LAUNCHED',
          preparationRunId: 'preparation-reused', errorCode: null, updatedAt: '2026-08-13T00:00:00Z',
        }])
      }
      if (path.endsWith('/api/v1/documents')) return response([{
        document: { id: 'document-1', title: 'rules.pdf', createdBy: 'player' },
        latestVersion: { id: refreshes === 1 ? 'version-1' : 'version-2', status: 'READY' },
      }])
      if (path.endsWith('/api/v1/assistant-runs/preparation-reused')) {
        preparationReads += 1
        const versionId = preparationReads === 1 ? 'version-1' : 'version-2'
        return response({ run: preparationRun('preparation-reused', versionId, 'player', 'COMPLETED') })
      }
      return new Response(null, { status: 404 })
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    expect(preparationReads).toBe(1)

    await vi.advanceTimersByTimeAsync(15_000)
    await flushPromises()

    expect(preparationReads).toBe(2)
    wrapper.unmount()
  })

  it('aborts reads while hidden, resumes once visible, and never reschedules after unmount', async () => {
    vi.useFakeTimers()
    const pending: Array<() => void> = []
    const signals: AbortSignal[] = []
    let activeReads = 0
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/v1/assistant-runs/active')) {
        activeReads += 1
        if (init?.signal) signals.push(init.signal)
        return new Promise<Response>((resolve) => pending.push(() => resolve(response([]))))
      }
      if (isBackgroundBaseList(path)) return Promise.resolve(response([]))
      return Promise.resolve(new Response(null, { status: 404 }))
    }))
    const wrapper = await mountCenter('player')
    await flushPromises()
    expect(activeReads).toBe(1)

    setVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(signals[0]?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(activeReads).toBe(1)

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(activeReads).toBe(2)

    wrapper.unmount()
    expect(signals[1]?.aborted).toBe(true)
    pending.forEach(resolve => resolve())
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(activeReads).toBe(2)
  })
})

async function mountCenter(username: string) {
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
  return mount(BackgroundWorkCenter, { props: { username }, global: { plugins: [router] } })
}

async function openCenter(wrapper: ReturnType<typeof mount>) {
  ;(wrapper.vm as unknown as { openCenter: () => void }).openCenter()
  await wrapper.vm.$nextTick()
}

function teachingRun(id: string, subjectId: string, ownerUsername: string, state: string) {
  return { id, mode: 'TEACHING', subjectId, ownerUsername, state }
}

function preparationRun(id: string, subjectId: string, ownerUsername: string, state: string) {
  return { id, mode: 'TEACHING_PREPARATION', subjectId, ownerUsername, state }
}

function teachingActivity(sequence: number, operation: string, outcome: string, summary: string) {
  return {
    sequence, type: operation.startsWith('publish') ? 'VALIDATION' : 'MODEL', operation, summary, outcome,
    latencyMs: 10, occurredAt: `2026-08-20T06:00:0${sequence}Z`,
  }
}

function isBackgroundBaseList(path: string) {
  return path.endsWith('/api/v1/documents/official-imports')
    || path.endsWith('/api/v1/documents/upload-teaching-handoffs')
    || path.endsWith('/api/v1/documents')
}

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function setVisibility(value: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value })
}
