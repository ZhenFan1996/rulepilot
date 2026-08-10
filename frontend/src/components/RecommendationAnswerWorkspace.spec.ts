import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonAnswerPanel from './LessonAnswerPanel.vue'
import RecommendationAnswerWorkspace from './RecommendationAnswerWorkspace.vue'
import { setLocale } from '@/lib/locale'

const answer = {
  status: 'ANSWERED' as const,
  shortVerdict: 'Resolve the bird power after gaining food.',
  explanation: 'The cited sequence places the power after the gain.',
  citations: [{ chunkId: 'chunk-1', sectionType: 'TURN', heading: 'Taking food', excerpt: 'Gain food, then activate powers.', pageFrom: 7, pageTo: 7 }],
  exceptions: [],
  confidence: 'HIGH' as const,
  answerBasis: 'DIRECT_RULE' as const,
  official: false,
  confirmedRulingId: null,
  confirmedRulingVersion: null,
  clarification: null,
  warnings: [],
}

const AnswerPanelStub = defineComponent({
  name: 'LessonAnswerPanel',
  props: {
    question: { type: String, required: true },
    answer: { type: Object, required: true },
    answerTurns: { type: Array, default: () => [] },
  },
  emits: ['update:question', 'ask', 'clearThread'],
  template: '<div data-testid="answer-panel-stub"><span>{{ answer?.shortVerdict }}</span><span>{{ answerTurns.length }}</span></div>',
})

describe('RecommendationAnswerWorkspace', () => {
  beforeEach(() => {
    localStorage.clear()
    setLocale('en')
  })

  afterEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  function mountWorkspace(active = true) {
    return mount(RecommendationAnswerWorkspace, {
      props: {
        active,
        documentVersionId: 'document-1',
        planId: 'plan-1',
        editionId: 'edition-1',
        gameTitle: 'Wingspan',
      },
      global: {
        stubs: {
          LessonAnswerPanel: AnswerPanelStub,
          CardOcrCapture: true,
        },
      },
    })
  }

  it('creates one rulebook-bound session and attaches it to every answer request', async () => {
    const requests: Array<{ path: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      requests.push({ path, init })
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/game-sessions' && init?.method === 'POST') return Response.json({
        id: 'session-1', editionId: 'edition-1', documentVersionId: 'document-1',
      })
      if (path.includes('/answers/conversation?')) return Response.json([])
      if (path === '/api/v1/document-versions/document-1/answers' && init?.method === 'POST') return Response.json({
        assistantRunId: 'run-1', answer, conversationTurnId: 'turn-1',
      })
      if (path === '/api/v1/assistant-runs/run-1') return Response.json({
        run: { id: 'run-1', subjectId: 'document-1', createdAt: new Date().toISOString() }, activities: [],
      })
      return new Response(null, { status: 404 })
    }))
    const wrapper = mountWorkspace()
    await flushPromises()

    const create = requests.find(request => request.path === '/api/v1/game-sessions' && request.init?.method === 'POST')
    expect(JSON.parse(String(create?.init?.body))).toMatchObject({
      editionId: 'edition-1',
      documentVersionId: 'document-1',
      expansionIds: [],
      playerCount: 1,
    })
    expect(localStorage.getItem('rulepilot:recommendation-answer-session:document-1')).toBe('session-1')

    const panel = wrapper.findComponent(LessonAnswerPanel)
    panel.vm.$emit('update:question', 'When does the bird power resolve?')
    await nextTick()
    panel.vm.$emit('ask')
    await flushPromises()

    const submitted = requests.find(request => request.path === '/api/v1/document-versions/document-1/answers' && request.init?.method === 'POST')
    expect(JSON.parse(String(submitted?.init?.body))).toMatchObject({
      question: 'When does the bird power resolve?',
      gameSessionId: 'session-1',
      language: 'en',
    })
    expect(wrapper.text()).toContain('Resolve the bird power after gaining food.')
  })

  it('restores the server-side conversation and does not create a second session when toggled away and back', async () => {
    localStorage.setItem('rulepilot:recommendation-answer-session:document-1', 'session-existing')
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/v1/game-sessions/session-existing') return Response.json({
        id: 'session-existing', editionId: 'edition-1', documentVersionId: 'document-1',
      })
      if (path.includes('/answers/conversation?')) return Response.json([{
        id: 'turn-1', question: 'When does it resolve?', answer, createdAt: '2026-08-10T00:00:00Z', feedback: null,
      }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/game-sessions' && init?.method === 'POST') return Response.json({
        id: 'session-new', editionId: 'edition-1', documentVersionId: 'document-1',
      })
      return new Response(null, { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('Resolve the bird power after gaining food.')
    expect(wrapper.findComponent(LessonAnswerPanel).props('answerTurns')).toHaveLength(1)
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(fetchMock.mock.calls.filter(([input, init]) => String(input) === '/api/v1/game-sessions' && init?.method === 'POST')).toHaveLength(0)
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/answers/conversation?'))).toHaveLength(1)
  })

  it('waits to create a paid-answer context until the player actually switches to Q&A', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountWorkspace(false)
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    await wrapper.setProps({ active: true })
    await flushPromises()

    expect(fetchMock).toHaveBeenCalled()
  })
})
