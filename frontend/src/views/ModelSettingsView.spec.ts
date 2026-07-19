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
        { id: 'compatible', configured: false, baseUrl: 'http://localhost:11434/v1', model: 'local-model', apiKeyConfigured: false, visionCapable: false },
      ],
      assignments: { teaching: 'gemini', answer: 'fake', critic: 'fake' },
      revision: 1,
      volatileSecrets: true,
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
    expect(wrapper.get('input[autocomplete="new-password"]').attributes('type')).toBe('password')
    expect(wrapper.text()).not.toContain('secret')
  })
})
