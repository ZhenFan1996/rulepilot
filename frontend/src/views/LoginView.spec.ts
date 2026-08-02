import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LoginView from './LoginView.vue'

describe('LoginView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('returns to the retained local page after an explicit successful login', async () => {
    vi.stubGlobal('fetch', successfulLoginFetch())
    const router = memoryRouter()
    await router.push('/login?redirect=/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router] } })

    expect(wrapper.get('input[name="username"]').element).toHaveProperty('value', '')
    await wrapper.get('input[name="username"]').setValue(' Player ')
    await wrapper.get('input[name="password"]').setValue('test-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    const loginRequest = vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes('/api/auth/login'))
    expect(String(loginRequest?.[1]?.body)).toContain('username=player')
  })

  it('does not follow an external redirect value', async () => {
    vi.stubGlobal('fetch', successfulLoginFetch())
    const router = memoryRouter()
    await router.push('/login?redirect=https://example.com')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/account')
  })
})

function memoryRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/lessons', name: 'lessons', component: { template: '<p>lessons</p>' } },
      { path: '/account', name: 'account', component: { template: '<p>account</p>' } },
      { path: '/', name: 'home', component: { template: '<p>home</p>' } },
      { path: '/register', name: 'register', component: { template: '<p>register</p>' } },
    ],
  })
}

function successfulLoginFetch() {
  return vi.fn(async (input: string | URL | Request) => {
    if (String(input).includes('/api/auth/csrf')) {
      return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    }
    return new Response(null, { status: 204 })
  })
}

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
