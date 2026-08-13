import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { backgroundWorkStorageKeys } from '@/lib/backgroundTeachingStatus'
import { notifyBackgroundWorkChanged } from '@/lib/backgroundWorkRefresh'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import BackgroundWorkCenter from './BackgroundWorkCenter.vue'

enableAutoUnmount(afterEach)

describe('BackgroundWorkCenter request lifecycle', () => {
  afterEach(() => {
    sessionStorage.clear()
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
