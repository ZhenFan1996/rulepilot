import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import ModelSettingsView from './ModelSettingsView.vue'

afterEach(() => vi.restoreAllMocks())

describe('ModelSettingsView', () => {
  it('loads redacted provider state and renders a password-only key field', async () => {
    const snapshot = {
      providers: [
        { id: 'gemini', configured: true, baseUrl: '', model: 'gemini-2.5-flash', apiKeyConfigured: true, visionCapable: true },
        { id: 'openai', configured: false, baseUrl: 'https://api.openai.com', model: 'gpt-5-mini', apiKeyConfigured: false, visionCapable: true },
        { id: 'deepseek', configured: false, baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', apiKeyConfigured: false, visionCapable: false },
        { id: 'qwen', configured: false, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3-vl-plus', apiKeyConfigured: false, visionCapable: true },
        { id: 'compatible', configured: false, baseUrl: 'http://localhost:11434/v1', model: 'local-model', apiKeyConfigured: false, visionCapable: false },
      ],
      assignments: { recommendation: 'deepseek', teaching: 'deepseek', visual: 'gemini', answer: 'fake', critic: 'fake' },
      revision: 1,
      volatileSecrets: true,
      managedStartupAccess: true,
    }
    vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string | URL | Request) => {
      const path = String(input)
      const body = path.includes('/api/auth/session')
        ? { username: 'player', roles: ['USER'] }
        : snapshot
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: ModelSettingsView },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(ModelSettingsView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('已配置')
    expect(wrapper.text()).toContain('规则书页面视觉')
    expect(wrapper.text()).toContain('Qwen')
    expect(wrapper.text()).toContain('基础讲解发布后')
    expect(wrapper.text()).toContain('服务端白名单')
    expect(wrapper.get('input[autocomplete="new-password"]').attributes('type')).toBe('password')
    expect(wrapper.text()).not.toContain('secret')

    await wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('Qwen'))!.trigger('click')
    expect(wrapper.text()).toContain('Qwen VL 可以读取规则书页面图片')
    expect((wrapper.get('input[type="url"]').element as HTMLInputElement).value)
      .toBe('https://dashscope.aliyuncs.com/compatible-mode/v1')
    expect((wrapper.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true)
  })

  it('requires explicit confirmation before disabling an unreadable saved key and retries in place after failure', async () => {
    const configured = () => ({
      providers: [
        { id: 'gemini', configured: true, baseUrl: '', model: 'gemini-2.5-flash', apiKeyConfigured: true, visionCapable: true },
      ],
      assignments: { recommendation: 'gemini', teaching: 'gemini', visual: 'gemini', answer: 'gemini', critic: 'gemini' },
      revision: 1,
      volatileSecrets: true,
      managedStartupAccess: false,
    })
    let disableAttempts = 0
    const fetchMock = vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/model-configuration/providers/gemini' && options?.method === 'DELETE') {
        disableAttempts += 1
        if (disableAttempts === 1) return Response.json({ detail: '暂时无法停用连接。' }, { status: 503 })
        const next = configured()
        next.providers[0]!.configured = false
        next.providers[0]!.apiKeyConfigured = false
        next.assignments = { recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake' }
        next.revision = 2
        return Response.json(next)
      }
      if (path === '/api/v1/model-configuration') return Response.json(configured())
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: ModelSettingsView },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(ModelSettingsView, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '停用')!.trigger('click')
    await flushPromises()
    expect(disableAttempts).toBe(0)
    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')!
    expect(dialog.textContent).toContain('API Key')
    expect(document.activeElement?.textContent).toContain('保留连接')

    ;[...dialog.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('停用连接'))!.click()
    await flushPromises()
    expect(disableAttempts).toBe(1)
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('暂时无法停用连接')

    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('重新尝试停用'))!.click()
    await flushPromises()
    expect(disableAttempts).toBe(2)
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(wrapper.text()).toContain('Gemini 已停用')
    expect(document.activeElement).toBe(wrapper.get('h1').element)
    wrapper.unmount()
  })
})
