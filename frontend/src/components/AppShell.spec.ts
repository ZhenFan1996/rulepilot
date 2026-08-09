import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { SESSION_CLEARED_EVENT, notifyLoginRequired } from '@/lib/authSession'
import { notifyTeachingLaunched } from '@/lib/teachingLaunch'
import AppShell from './AppShell.vue'

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
        return response(activeReads === 1 ? [{ id: 'run-1', subjectId: 'plan-1', state: 'LESSON_COMPOSITION' }] : [])
      }
      if (path.includes('/api/v1/assistant-runs/run-1')) {
        return response({ run: { id: 'run-1', state: 'COMPLETED' } })
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-1', gameTitle: '星际探索' }])
      }
      if (path.endsWith('/api/v1/documents/official-imports') || path.endsWith('/api/v1/documents')) return response([])
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

    await wrapper.get('button[aria-label="后台任务"]').trigger('click')
    expect(wrapper.text()).toContain('星际探索')
    expect(wrapper.text()).toContain('可以继续浏览')
    expect(wrapper.text()).toContain('公开讲解')
    expect(wrapper.text()).toContain('我的讲解')
    expect(wrapper.get('button[aria-label="后台任务"]').text()).toContain('1')
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('中文')
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('EN')

    vi.advanceTimersByTime(4000)
    await flushPromises()

    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).not.toContain('生成成功')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/teaching-plans'))).toHaveLength(1)

    await wrapper.findAll('button').find(button => button.text() === '清除已结束任务')!.trigger('click')
    expect(wrapper.text()).not.toContain('星际探索')
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
    sessionStorage.setItem('rulepilot:active-teaching-runs', JSON.stringify([
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
          : response({ run: { id: 'run-1', state: 'COMPLETED' } })
      }
      if (path.endsWith('/api/v1/documents/official-imports') || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('button[aria-label="后台任务"]').trigger('click')
    expect(wrapper.text()).toContain('星际探索')
    expect(wrapper.text()).not.toContain('后台处理已经结束')

    await vi.advanceTimersByTimeAsync(4000)
    await flushPromises()

    expect(wrapper.text()).toContain('已完成')
    wrapper.unmount()
  })

  it('starts background status tracking immediately when teaching is launched', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return response({ username: 'player' })
      if (path.includes('/api/v1/assistant-runs/active')) return response([])
      if (path.includes('/api/v1/assistant-runs/run-2')) {
        return response({ run: { id: 'run-2', state: 'RECEIVED' } })
      }
      if (path.endsWith('/api/v1/documents/official-imports') || path.endsWith('/api/v1/documents')) return response([])
      return new Response(null, { status: 404 })
    }))
    const router = createAppShellRouter()
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, { slots: { default: '<p>页面内容</p>' }, global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('button[aria-label="后台任务"]').text()).not.toContain('1')

    notifyTeachingLaunched({ planId: 'plan-2', runId: 'run-2', gameTitle: '卡坦岛' })
    await flushPromises()

    await wrapper.get('button[aria-label="后台任务"]').trigger('click')
    expect(wrapper.text()).toContain('卡坦岛')
    expect(sessionStorage.getItem('rulepilot:active-teaching-runs')).toContain('run-2')
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
        return response(activeReads === 1 ? [] : [{ id: 'run-other-tab', subjectId: 'plan-other-tab' }])
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-other-tab', gameTitle: '跨标签页规则书' }])
      }
      if (path.endsWith('/api/v1/documents/official-imports') || path.endsWith('/api/v1/documents')) return response([])
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
    await wrapper.get('button[aria-label="后台任务"]').trigger('click')
    expect(wrapper.text()).toContain('跨标签页规则书')
    expect(sessionStorage.getItem('rulepilot:active-teaching-runs')).toContain('run-other-tab')
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

  it('clears account-owned notices and the active route state after logout succeeds', async () => {
    const sessionCleared = vi.fn()
    window.addEventListener(SESSION_CLEARED_EVENT, sessionCleared)
    sessionStorage.setItem('rulepilot:active-teaching-runs', JSON.stringify([
      { runId: 'run-1', planId: 'plan-1', gameTitle: 'Private lesson' },
    ]))
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
    expect(sessionStorage.getItem('rulepilot:active-teaching-runs')).toBeNull()
    expect(wrapper.text()).not.toContain('player')
    window.removeEventListener(SESSION_CLEARED_EVENT, sessionCleared)
    wrapper.unmount()
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
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
}
