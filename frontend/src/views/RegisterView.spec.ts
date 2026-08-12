import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RegisterView from './RegisterView.vue'

describe('RegisterView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('carries a local return path through account creation and automatic sign-in', async () => {
    vi.stubGlobal('fetch', successfulRegistrationFetch())
    const router = memoryRouter()
    await router.push('/register?redirect=/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })

    expect(wrapper.get('[data-testid="auth-return-context"]').text()).toContain('回到刚才的页面')
    expect(wrapper.get('a[href="/login?redirect=/lessons?filter=pending"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue(' Player ')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    const loginRequest = vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes('/api/auth/login'))
    expect(String(loginRequest?.[1]?.body)).toContain('username=player')
    wrapper.unmount()
  })

  it('does not expose or follow an authentication or external return path', async () => {
    vi.stubGlobal('fetch', successfulRegistrationFetch())
    const router = memoryRouter()
    await router.push('/register?redirect=/login?redirect=https://example.com')
    await router.isReady()
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })

    expect(wrapper.find('[data-testid="auth-return-context"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/login"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/account')
    wrapper.unmount()
  })

  it('retains the destination and fields when registration can be retried', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/csrf')) {
        return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      }
      return new Response(null, { status: 409 })
    }))
    const router = memoryRouter()
    await router.push('/register?redirect=/catalog?view=ready%23collection')
    await router.isReady()
    const wrapper = mount(RegisterView, { global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('already-used')
    await wrapper.get('input[name="password"]').setValue('retry-password')
    await wrapper.get('input[name="confirmation"]').setValue('retry-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/register?redirect=/catalog?view=ready%23collection')
    expect(wrapper.get('[role="alert"]').text()).toContain('这个用户名已经有人使用')
    expect((wrapper.get('input[name="username"]').element as HTMLInputElement).value).toBe('already-used')
    expect((wrapper.get('input[name="password"]').element as HTMLInputElement).value).toBe('retry-password')
    expect(wrapper.get('a[href="/login?redirect=/catalog?view=ready%23collection"]')).toBeTruthy()
    wrapper.unmount()
  })
})

function memoryRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', name: 'register', component: RegisterView },
      { path: '/login', name: 'login', component: { template: '<p>login</p>' } },
      { path: '/lessons', name: 'lessons', component: { template: '<p>lessons</p>' } },
      { path: '/account', name: 'account', component: { template: '<p>account</p>' } },
      { path: '/', name: 'home', component: { template: '<p>home</p>' } },
    ],
  })
}

function successfulRegistrationFetch() {
  return vi.fn(async (input: string | URL | Request) => {
    if (String(input).includes('/api/auth/csrf')) {
      return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    }
    return new Response(null, { status: String(input).includes('/api/auth/register') ? 201 : 204 })
  })
}

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
