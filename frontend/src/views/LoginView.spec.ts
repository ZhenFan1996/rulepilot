import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LoginView from './LoginView.vue'

describe('LoginView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('returns to the retained local page after an explicit successful login', async () => {
    vi.stubGlobal('fetch', successfulLoginFetch())
    const router = memoryRouter()
    await router.push('/login?redirect=/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [router] } })

    expect(wrapper.get('input[name="username"]').element).toHaveProperty('value', '')
    expect(wrapper.get('[data-testid="auth-return-context"]').text()).toContain('回到刚才的页面')
    expect(wrapper.get('a[href="/register?redirect=/lessons?filter=pending"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue(' Player ')
    await wrapper.get('input[name="password"]').setValue('test-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    const loginRequest = vi.mocked(fetch).mock.calls.find(([input]) => String(input).includes('/api/auth/login'))
    expect(String(loginRequest?.[1]?.body)).toContain('username=player')
    wrapper.unmount()
  })

  it('falls back to the player entry instead of account for an invalid redirect value', async () => {
    vi.stubGlobal('fetch', successfulLoginFetch())
    const router = memoryRouter()
    await router.push('/login?redirect=https://example.com')
    await router.isReady()
    const wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [router] } })

    expect(wrapper.find('[data-testid="auth-return-context"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/register"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('falls back to the player entry when a once-local return route no longer exists', async () => {
    vi.stubGlobal('fetch', successfulLoginFetch())
    const router = memoryRouter()
    await router.push('/login?redirect=/retired-player-area')
    await router.isReady()
    const wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [router] } })

    expect(wrapper.find('[data-testid="auth-return-context"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/register"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
    wrapper.unmount()
  })

  it('retains the destination and form values after a failed sign-in', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => {
      if (String(input).includes('/api/auth/csrf')) {
        return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      }
      return new Response(null, { status: 401 })
    }))
    const router = memoryRouter()
    await router.push('/login?redirect=/catalog?view=ready%23collection')
    await router.isReady()
    const wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('retry-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/catalog?view=ready%23collection')
    expect(wrapper.get('[role="alert"]').text()).toContain('用户名或密码不正确')
    const username = wrapper.get('input[name="username"]')
    const password = wrapper.get('input[name="password"]')
    expect((username.element as HTMLInputElement).value).toBe('alice')
    expect((wrapper.get('input[name="password"]').element as HTMLInputElement).value).toBe('retry-password')
    expect(username.attributes('aria-invalid')).toBe('true')
    expect(password.attributes('aria-invalid')).toBe('true')
    expect(username.attributes('aria-describedby')).toBe('auth-login-error')
    expect(document.activeElement).toBe(username.element)
    expect(wrapper.get('a[href="/register?redirect=/catalog?view=ready%23collection"]')).toBeTruthy()

    await username.setValue('alice-fixed')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(username.attributes('aria-invalid')).toBeUndefined()
    wrapper.unmount()
  })

  it('localizes a network exception, focuses its summary, and locks duplicate submission', async () => {
    let rejectCsrf!: (reason: unknown) => void
    const csrfRequest = new Promise<Response>((_, reject) => { rejectCsrf = reject })
    const fetchMock = vi.fn(async () => csrfRequest)
    vi.stubGlobal('fetch', fetchMock)
    const router = memoryRouter()
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('form').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('input[name="username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[name="password"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(fetchMock).toHaveBeenCalledOnce()

    rejectCsrf(new TypeError('Failed to fetch'))
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('暂时无法连接')
    expect(alert.text()).not.toContain('Failed to fetch')
    expect(document.activeElement).toBe(alert.element)
    expect(wrapper.get('form').attributes('aria-busy')).toBe('false')
    wrapper.unmount()
  })
})

function memoryRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/lessons', name: 'lessons', component: { template: '<p>lessons</p>' } },
      { path: '/catalog', name: 'catalog', component: { template: '<p>catalog</p>' } },
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
