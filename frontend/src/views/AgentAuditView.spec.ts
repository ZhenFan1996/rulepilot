import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AgentAuditView from './AgentAuditView.vue'

describe('AgentAuditView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders the complete safe activity audit for an administrator', async () => {
    const runId = '11111111-1111-4111-8111-111111111111'
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path === '/api/auth/session') return Response.json({ username: 'admin', roles: ['ADMIN'] })
      if (path.includes('/api/v1/assistant-runs/active')) return Response.json([])
      if (path.includes(`/api/admin/assistant-runs/${runId}/audit`)) {
        return Response.json({
          run: { id: runId, mode: 'QUESTION_ANSWER', subjectId: runId, ownerUsername: 'player', state: 'COMPLETED', createdAt: '2026-08-03T00:00:00Z', updatedAt: '2026-08-03T00:00:01Z', lastErrorCode: null },
          budget: { maxSteps: 40, maxToolCalls: 24, maxModelCalls: 16, maxTokens: 24000, usedToolCalls: 2, usedModelCalls: 2, usedTokens: 500, deadlineAt: '2026-08-03T00:02:00Z' },
          steps: [{ sequence: 1, fromState: 'RECEIVED', toState: 'COMPLETED', summary: 'Answer published', occurredAt: '2026-08-03T00:00:01Z' }],
          activities: [{ sequence: 1, type: 'TOOL', operation: 'nativeTool|read_rule_pages|safehash', outcome: 'SUCCEEDED', estimatedInputTokens: 10, estimatedOutputTokens: 20, latencyMs: 12, summary: 'code=PAGE_EVIDENCE_FOUND evidenceCount=2', occurredAt: '2026-08-03T00:00:00Z' }],
        })
      }
      return Response.json({})
    }))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/agent-audit', component: AgentAuditView }] })
    await router.push(`/admin/agent-audit?runId=${runId}`)
    await router.isReady()
    const wrapper = mount(AgentAuditView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })

    await vi.waitFor(() => expect(wrapper.text()).toContain('PAGE_EVIDENCE_FOUND'))

    expect(wrapper.text()).toContain('nativeTool|read_rule_pages|safehash')
    expect(wrapper.text()).toContain('2 / 24')
    expect(wrapper.text()).toContain('不会保存或展示隐藏思维链')
    expect(fetch).toHaveBeenCalledWith(`/api/admin/assistant-runs/${runId}/audit`, { credentials: 'include' })
  })

  it('explains that authentication is required instead of reporting a generic outage', async () => {
    const runId = '22222222-2222-4222-8222-222222222222'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/agent-audit', component: AgentAuditView }] })
    await router.push(`/admin/agent-audit?runId=${runId}`)
    await router.isReady()

    const wrapper = mount(AgentAuditView, { global: { plugins: [router], stubs: { AppShell: { template: '<div><slot /></div>' } } } })

    await vi.waitFor(() => expect(wrapper.text()).toContain('请先登录管理员账号'))
  })
})
