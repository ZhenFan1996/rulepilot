import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { notifyLoginRequired } from '@/lib/authSession'
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
        return response(activeReads === 1 ? [{ id: 'run-1', subjectId: 'plan-1' }] : [])
      }
      if (path.includes('/api/v1/teaching-plans')) {
        return response([{ id: 'plan-1', gameTitle: '星际探索' }])
      }
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

    expect(wrapper.text()).toContain('《星际探索》仍在后台准备')
    expect(wrapper.text()).toContain('可以继续浏览')
    expect(wrapper.text()).toContain('公开讲解')
    expect(wrapper.text()).toContain('我的讲解')
    expect(wrapper.findAll('[aria-label="1 份讲解正在生成"]')).toHaveLength(2)
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('中文')
    expect(wrapper.get('header [aria-label="切换语言"]').text()).toContain('EN')

    vi.advanceTimersByTime(5000)
    await flushPromises()

    expect(wrapper.text()).toContain('《星际探索》的后台处理已经结束')
    expect(wrapper.text()).toContain('查看实际结果')
    expect(wrapper.text()).not.toContain('生成成功')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/teaching-plans'))).toHaveLength(1)

    await wrapper.get('button[aria-label="关闭讲解完成提醒"]').trigger('click')
    expect(wrapper.text()).not.toContain('后台处理已经结束')
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
})

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
