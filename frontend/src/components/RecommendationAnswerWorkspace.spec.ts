import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonAnswerPanel from './LessonAnswerPanel.vue'
import RecommendationAnswerWorkspace from './RecommendationAnswerWorkspace.vue'
import { setLocale } from '@/lib/locale'

const answer = {
  language: 'en' as const,
  status: 'ANSWERED' as const,
  shortVerdict: 'Resolve the bird power after gaining food.',
  explanation: 'The cited sequence places the power after the gain.',
  citations: [{ heading: 'Taking food', excerpt: 'Gain food, then activate powers.', pageFrom: 7, pageTo: 7 }],
  exceptions: [],
  confidence: 'HIGH' as const,
  answerBasis: 'DIRECT_RULE' as const,
  source: 'UPLOADED' as const,
  clarification: null,
  recovery: null,
  warnings: [],
}

const rulingReference = {
  citationIds: ['chunk-1'], confirmedRulingId: null, confirmedRulingVersion: null,
}

function answerStreamResponse(result: unknown) {
  return new Response(`event: result\ndata: ${JSON.stringify(result)}\n\n`, {
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

const AnswerPanelStub = defineComponent({
  name: 'LessonAnswerPanel',
  props: {
    question: { type: String, required: true },
    answer: { type: Object, required: true },
    answerTurns: { type: Array, default: () => [] },
  },
  emits: ['update:question', 'ask', 'clearThread'],
  setup(_props, { expose }) {
    const questionInput = ref<HTMLTextAreaElement | null>(null)
    expose({ focusQuestion: () => questionInput.value?.focus({ preventScroll: true }) })
    return { questionInput }
  },
  template: '<div data-testid="answer-panel-stub"><span>{{ answer?.shortVerdict }}</span><span>{{ answerTurns.length }}</span><textarea ref="questionInput" data-testid="question-input" :value="question" @input="$emit(\'update:question\', $event.target.value)" /><button v-if="answerTurns.length" data-testid="clear-thread" type="button" @click="$emit(\'clearThread\')">Clear Q&amp;A</button></div>',
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
    document.body.innerHTML = ''
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

  it('keeps the interactive workspace out of a broad live region', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
    const wrapper = mountWorkspace()

    expect(wrapper.get('[data-testid="recommendation-answer-workspace"]').attributes('aria-live'))
      .toBeUndefined()
    expect(wrapper.get('[role="status"]').text()).toContain('Restoring')
    wrapper.unmount()
  })

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
      if (path === '/api/v1/document-versions/document-1/answers/stream' && init?.method === 'POST') return answerStreamResponse({
        answer, conversationTurnId: 'turn-1', rulingReference,
      })
      return new Response(null, { status: 404 })
    }))
    const wrapper = mountWorkspace()
    await flushPromises()

    expect(wrapper.text()).toContain('Rules Q&A for Wingspan')
    expect(wrapper.text()).not.toContain('Rules Q&A Agent')
    expect(wrapper.text()).not.toContain('Answering from Wingspan')

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

    const submitted = requests.find(request => request.path === '/api/v1/document-versions/document-1/answers/stream' && request.init?.method === 'POST')
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
        id: 'turn-1', question: 'When does it resolve?', answer: {
          ...answer,
          documentVersionId: '11111111-1111-4111-8111-111111111111',
          citations: answer.citations.map(citation => ({ ...citation, chunkId: 'chunk-1' })),
        },
        rulingReference,
        createdAt: '2026-08-10T00:00:00Z', feedback: null,
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
    expect(JSON.stringify(wrapper.findComponent(LessonAnswerPanel).props('answerTurns')))
      .not.toMatch(/documentVersionId|chunkId|11111111-1111-4111-8111-111111111111/)
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

  it('keeps the current server thread and draft until a replacement session succeeds', async () => {
    localStorage.setItem('rulepilot:recommendation-answer-session:document-1', 'session-existing')
    let replacementAttempt = 0
    const requests: Array<{ path: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const path = String(input)
      requests.push({ path, init })
      if (path === '/api/v1/game-sessions/session-existing') return Response.json({
        id: 'session-existing', editionId: 'edition-1', documentVersionId: 'document-1',
      })
      if (path.includes('gameSessionId=session-existing')) return Response.json([{
        id: 'turn-1', question: 'When does it resolve?', answer, rulingReference,
        createdAt: '2026-08-10T00:00:00Z',
      }])
      if (path === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' })
      if (path === '/api/v1/game-sessions' && init?.method === 'POST') {
        replacementAttempt += 1
        if (replacementAttempt === 1) return new Response(null, { status: 503 })
        return Response.json({ id: 'session-new', editionId: 'edition-1', documentVersionId: 'document-1' })
      }
      if (path.includes('gameSessionId=session-new')) return Response.json([])
      return new Response(null, { status: 404 })
    }))
    const wrapper = mount(RecommendationAnswerWorkspace, {
      attachTo: document.body,
      props: {
        active: true,
        documentVersionId: 'document-1',
        planId: 'plan-1',
        editionId: 'edition-1',
        gameTitle: 'Wingspan',
      },
      global: { stubs: { LessonAnswerPanel: AnswerPanelStub, CardOcrCapture: true } },
    })
    await flushPromises()

    const panel = wrapper.findComponent(LessonAnswerPanel)
    panel.vm.$emit('update:question', 'An unsubmitted follow-up')
    await nextTick()
    await wrapper.get('[data-testid="clear-thread"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('will not be deleted from the server')
    expect(wrapper.text()).toContain('Resolve the bird power after gaining food.')
    expect(localStorage.getItem('rulepilot:recommendation-answer-session:document-1')).toBe('session-existing')
    expect(replacementAttempt).toBe(0)

    Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === 'Start new Q&A')!
      .click()
    await flushPromises()

    expect(document.body.textContent).toContain('The rules Q&A session could not be created or restored.')
    expect(document.body.textContent).toContain('Try creating it again')
    expect(wrapper.text()).toContain('Resolve the bird power after gaining food.')
    expect(wrapper.get('[data-testid="question-input"]').element).toHaveProperty('value', 'An unsubmitted follow-up')
    expect(localStorage.getItem('rulepilot:recommendation-answer-session:document-1')).toBe('session-existing')

    Array.from(document.body.querySelectorAll('button'))
      .find(button => button.textContent === 'Try creating it again')!
      .click()
    await flushPromises()

    expect(document.body.textContent).not.toContain('Start a new Q&A for Wingspan?')
    expect(wrapper.text()).not.toContain('Resolve the bird power after gaining food.')
    expect(wrapper.get('[data-testid="question-input"]').element).toHaveProperty('value', 'An unsubmitted follow-up')
    expect(document.activeElement).toBe(wrapper.get('[data-testid="question-input"]').element)
    expect(localStorage.getItem('rulepilot:recommendation-answer-session:document-1')).toBe('session-new')
    expect(replacementAttempt).toBe(2)
    expect(requests.some(request => request.init?.method === 'DELETE')).toBe(false)
    wrapper.unmount()
  })
})
