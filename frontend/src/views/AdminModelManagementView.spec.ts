import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AdminModelManagementView from './AdminModelManagementView.vue'

describe('AdminModelManagementView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows redacted platform connections and updates one account quota with CSRF', async () => {
    const calls: Array<{ path: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      calls.push({ path, init })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/admin/model-configuration') return Response.json(snapshot())
      if (path === '/api/admin/model-configuration/accounts') return Response.json([account()])
      if (path.endsWith('/accounts/alice/quota')) return Response.json({ ...account().usage, monthlyTokenLimit: 3000000, platformTokensRemaining: 2875000 })
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(AdminModelManagementView, { global: { stubs: { AppShell: { template: '<div><slot /></div>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('模型与账户额度')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('125,000')
    expect(wrapper.get('input[type="password"]').attributes('autocomplete')).toBe('new-password')
    expect(wrapper.text()).not.toContain('existing-secret')

    const quotaInput = wrapper.get('input[type="number"]')
    await quotaInput.setValue('3000000')
    await wrapper.findAll('button').find(button => button.text() === '保存')!.trigger('click')
    await flushPromises()

    const update = calls.find(call => call.path.endsWith('/accounts/alice/quota'))
    expect(update?.init?.method).toBe('PUT')
    expect(update?.init?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf' })
    expect(JSON.parse(String(update?.init?.body))).toEqual({ platformAccessEnabled: true, monthlyTokenLimit: 3000000 })
  })
})

function snapshot() {
  return {
    providers: [
      { id: 'qwen', configured: true, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen3.7-plus', apiKeyConfigured: true, visionCapable: true, credentialSource: 'PLATFORM' },
      { id: 'deepseek', configured: false, baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash', apiKeyConfigured: false, visionCapable: false, credentialSource: 'NONE' },
    ],
    assignments: { recommendation: 'qwen', teaching: 'qwen', visual: 'qwen', answer: 'qwen', critic: 'fake' },
    revision: 1,
  }
}

function account() {
  return {
    username: 'alice', enabled: true, authorities: ['ROLE_USER'],
    usage: { username: 'alice', platformAccessEnabled: true, monthlyTokenLimit: 2000000, platformTokensCharged: 125000, platformTokensReserved: 0, personalTokensUsed: 42000, platformTokensRemaining: 1875000 },
  }
}
