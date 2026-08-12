import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import ModelSettingsView from './ModelSettingsView.vue'

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
  localStorage.clear()
  sessionStorage.clear()
})

function configurationSnapshot() {
  return {
    providers: [
      { id: 'gemini', configured: true, baseUrl: '', model: 'gemini-2.5-flash', apiKeyConfigured: true, visionCapable: true },
      { id: 'openai', configured: true, baseUrl: 'https://api.openai.com', model: 'gpt-5-mini', apiKeyConfigured: true, visionCapable: true },
      { id: 'deepseek', configured: false, baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', apiKeyConfigured: false, visionCapable: false },
      { id: 'qwen', configured: false, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3-vl-plus', apiKeyConfigured: false, visionCapable: true },
      { id: 'compatible', configured: false, baseUrl: 'http://localhost:11434/v1', model: 'local-model', apiKeyConfigured: false, visionCapable: false },
    ],
    assignments: { recommendation: 'openai', teaching: 'openai', visual: 'gemini', answer: 'gemini', critic: 'gemini' },
    revision: 1,
    volatileSecrets: true,
    managedStartupAccess: false,
  }
}

async function mountSettings() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: ModelSettingsView },
      { path: '/catalog', name: 'catalog', component: { template: '<h1>我的桌游</h1>' } },
      { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ModelSettingsView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return { router, wrapper }
}

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

  it('keeps per-provider secrets in page memory across tabs without browser persistence', async () => {
    const snapshot = configurationSnapshot()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      return Response.json(snapshot)
    }))
    const { wrapper } = await mountSettings()

    const key = wrapper.get('input[type="password"]')
    await key.setValue('memory-only-secret')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('Gemini 连接')
    expect(wrapper.text()).toContain('不会把 API Key 写入浏览器存储')

    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('OpenAI'))!.trigger('click')
    await wrapper.get('input[type="password"]').setValue('second-memory-secret')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('Gemini'))!.trigger('click')

    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('memory-only-secret')
    expect(wrapper.findAll('[data-testid="provider-unsaved"]')).toHaveLength(2)
    expect(JSON.stringify({ ...localStorage })).not.toContain('memory-only-secret')
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('memory-only-secret')
    wrapper.unmount()
  })

  it('saves one provider without erasing another provider or assignment draft', async () => {
    const baseline = configurationSnapshot()
    let submitted: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/providers/gemini') && options?.method === 'PUT') {
        submitted = JSON.parse(String(options.body)) as Record<string, unknown>
        return Response.json({
          ...baseline,
          providers: baseline.providers.map(entry => entry.id === 'gemini'
            ? { ...entry, model: 'gemini-custom' }
            : entry),
          revision: 2,
        })
      }
      return Response.json(baseline)
    }))
    const { wrapper } = await mountSettings()

    await wrapper.get('input[type="password"]').setValue('gemini-secret')
    await wrapper.get('input:not([type])').setValue('gemini-custom')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('OpenAI'))!.trigger('click')
    await wrapper.get('input[type="password"]').setValue('openai-secret')
    const teaching = wrapper.findAll('select').find(select => select.element.parentElement?.textContent?.includes('讲解文字与结构'))!
    await teaching.setValue('gemini')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('Gemini'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '保存连接')!.trigger('click')
    await flushPromises()

    expect(submitted).toMatchObject({ apiKey: 'gemini-secret', model: 'gemini-custom' })
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('OpenAI 连接')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('模型用途')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('OpenAI'))!.trigger('click')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('openai-secret')
    wrapper.unmount()
  })

  it('saves assignment drafts without erasing an unsaved provider secret', async () => {
    const baseline = configurationSnapshot()
    let submitted: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/assignments') && options?.method === 'PUT') {
        submitted = JSON.parse(String(options.body)) as Record<string, unknown>
        return Response.json({ ...baseline, assignments: submitted, revision: 2 })
      }
      return Response.json(baseline)
    }))
    const { wrapper } = await mountSettings()

    await wrapper.get('input[type="password"]').setValue('provider-draft-secret')
    const teaching = wrapper.findAll('select').find(select => select.element.parentElement?.textContent?.includes('讲解文字与结构'))!
    await teaching.setValue('gemini')
    await wrapper.findAll('button').find(button => button.text() === '保存用途设置')!.trigger('click')
    await flushPromises()

    expect(submitted).toMatchObject({ teaching: 'gemini' })
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('provider-draft-secret')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('Gemini 连接')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).not.toContain('模型用途')
    expect(wrapper.findAll('button').find(button => button.text() === '保存用途设置')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('clears only the disabled provider draft and preserves other selectable drafts', async () => {
    const baseline = configurationSnapshot()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path.endsWith('/providers/gemini') && options?.method === 'DELETE') {
        return Response.json({
          ...baseline,
          providers: baseline.providers.map(entry => entry.id === 'gemini'
            ? { ...entry, configured: false, apiKeyConfigured: false }
            : entry),
          assignments: { ...baseline.assignments, visual: 'fake', answer: 'fake', critic: 'fake' },
          revision: 2,
        })
      }
      return Response.json(baseline)
    }))
    const { wrapper } = await mountSettings()

    await wrapper.get('input[type="password"]').setValue('disabled-provider-draft')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('OpenAI'))!.trigger('click')
    await wrapper.get('input[type="password"]').setValue('preserved-provider-draft')
    const answer = wrapper.findAll('select').find(select => select.element.parentElement?.textContent?.includes('规则答疑'))!
    await answer.setValue('openai')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('Gemini'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停用')!.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain('页面草稿也会被清除')
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('停用连接'))!.click()
    await flushPromises()

    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).toContain('尚未连接')
    await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('OpenAI'))!.trigger('click')
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('preserved-provider-draft')
    expect((answer.element as HTMLSelectElement).value).toBe('openai')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('OpenAI 连接')
    expect(wrapper.get('[data-testid="model-settings-unsaved"]').text()).toContain('模型用途')
    wrapper.unmount()
  })

  it('guards dirty route navigation, restores the opener on cancel, and leaves only after discard', async () => {
    const snapshot = configurationSnapshot()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      return Response.json(snapshot)
    }))
    const { router, wrapper } = await mountSettings()
    await wrapper.get('input[type="password"]').setValue('leave-protected-secret')
    const opener = wrapper.findAll('a').find(link => link.attributes('href') === '/catalog')!
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')!
    expect(router.currentRoute.value.path).toBe('/')
    expect(dialog.textContent).toContain('Gemini 连接仍未保存')
    expect(document.activeElement?.textContent).toContain('继续编辑')
    ;[...dialog.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('继续编辑'))!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(document.activeElement).toBe(opener.element)
    expect((wrapper.get('input[type="password"]').element as HTMLInputElement).value).toBe('leave-protected-secret')

    await opener.trigger('click')
    await flushPromises()
    ;[...document.body.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')]
      .find(button => button.textContent?.includes('放弃更改并离开'))!.click()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/catalog')
  })

  it('registers beforeunload protection only while a draft or save is active', async () => {
    const snapshot = configurationSnapshot()
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/session')) return Response.json({ username: 'player', roles: ['USER'] })
      return Response.json(snapshot)
    }))
    const { wrapper } = await mountSettings()

    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    await wrapper.get('input[type="password"]').setValue('unload-protected-secret')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
    wrapper.unmount()

    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })
})
