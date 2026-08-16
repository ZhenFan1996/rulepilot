import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RegisterView from './RegisterView.vue'

describe('RegisterView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('carries a local return path through account creation and automatic sign-in', async () => {
    vi.stubGlobal('fetch', successfulRegistrationFetch())
    const router = memoryRouter()
    await router.push('/register?redirect=/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

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

  it('falls back to the player entry for an authentication or external return path', async () => {
    vi.stubGlobal('fetch', successfulRegistrationFetch())
    const router = memoryRouter()
    await router.push('/register?redirect=/login?redirect=https://example.com')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    expect(wrapper.find('[data-testid="auth-return-context"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/login"]')).toBeTruthy()
    await wrapper.get('input[name="username"]').setValue('alice')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
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
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('already-used')
    await wrapper.get('input[name="password"]').setValue('retry-password')
    await wrapper.get('input[name="confirmation"]').setValue('retry-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/register?redirect=/catalog?view=ready%23collection')
    expect(wrapper.get('[role="alert"]').text()).toContain('这个用户名已经有人使用')
    const username = wrapper.get('input[name="username"]')
    expect((username.element as HTMLInputElement).value).toBe('already-used')
    expect((wrapper.get('input[name="password"]').element as HTMLInputElement).value).toBe('retry-password')
    expect(username.attributes('aria-invalid')).toBe('true')
    expect(username.attributes('aria-describedby')).toContain('auth-register-error')
    expect(document.activeElement).toBe(username.element)
    expect(wrapper.get('a[href="/login?redirect=/catalog?view=ready%23collection"]')).toBeTruthy()

    await username.setValue('available-name')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(username.attributes('aria-invalid')).toBeUndefined()
    wrapper.unmount()
  })

  it('focuses and associates a local password-confirmation mismatch', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const router = memoryRouter()
    await router.push('/register')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('player')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('other-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const confirmation = wrapper.get('input[name="confirmation"]')
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('两次输入的密码不一致')
    expect(confirmation.attributes('aria-invalid')).toBe('true')
    expect(confirmation.attributes('aria-describedby')).toBe('auth-register-error')
    expect(document.activeElement).toBe(confirmation.element)
    wrapper.unmount()
  })

  it('retries only automatic sign-in after the account was created', async () => {
    let loginAttempt = 0
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const path = String(input)
      if (path.includes('/api/auth/csrf')) {
        return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      }
      if (path.includes('/api/auth/register')) return new Response(null, { status: 201 })
      loginAttempt += 1
      return new Response(null, { status: loginAttempt === 1 ? 503 : 204 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const router = memoryRouter()
    await router.push('/register?redirect=/lessons?filter=pending')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue(' New.Player ')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('账号已经创建')
    expect(alert.text()).toContain('不会再次创建账号')
    expect(document.activeElement).toBe(alert.element)
    expect(wrapper.get('button[type="submit"]').text()).toBe('重试登录')
    expect(wrapper.get('input[name="username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[name="password"]').attributes('disabled')).toBeDefined()
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/register'))).toHaveLength(1)

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/lessons?filter=pending')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/register'))).toHaveLength(1)
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/auth/login'))).toHaveLength(2)
    wrapper.unmount()
  })

  it('localizes an offline exception and focuses the retry summary', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    const router = memoryRouter()
    await router.push('/register')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('player')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('暂时无法连接')
    expect(alert.text()).not.toContain('Failed to fetch')
    expect(document.activeElement).toBe(alert.element)
    wrapper.unmount()
  })

  it('locks registration fields and ignores duplicate submission while the request is pending', async () => {
    let releaseCsrf!: (response: Response) => void
    const csrfRequest = new Promise<Response>((resolve) => { releaseCsrf = resolve })
    const fetchMock = vi.fn(async () => csrfRequest)
    vi.stubGlobal('fetch', fetchMock)
    const router = memoryRouter()
    await router.push('/register')
    await router.isReady()
    const wrapper = mount(RegisterView, { attachTo: document.body, global: { plugins: [router] } })

    await wrapper.get('input[name="username"]').setValue('player')
    await wrapper.get('input[name="password"]').setValue('test-password')
    await wrapper.get('input[name="confirmation"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('form').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('input[name="username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[name="password"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[name="confirmation"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(fetchMock).toHaveBeenCalledOnce()

    releaseCsrf(new Response(null, { status: 503 }))
    await flushPromises()
    expect(wrapper.get('form').attributes('aria-busy')).toBe('false')
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
      { path: '/catalog', name: 'catalog', component: { template: '<p>catalog</p>' } },
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
