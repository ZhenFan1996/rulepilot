import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import PublicLessonView from './PublicLessonView.vue'

function routerFor(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/library', name: 'public-library', component: { template: '<div />' } },
      { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
      { path: '/read/:planId/questions', name: 'public-lesson-questions', component: PublicLessonView },
      { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
      { path: '/discover', name: 'game-recommendations', component: { template: '<div />' } },
      { path: '/teach', name: 'teach', component: { template: '<div />' } },
      { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
      { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  return router.push(path).then(() => router.isReady()).then(() => router)
}

function lessonPayload() {
  return {
    teachingPlanId: 'plan-1', documentVersionId: 'version-1', rulebookTitle: '测试规则书',
    officialSourceUrl: null, gameCover: null, contentLanguage: 'zh-CN',
    unresolvedTopics: ['缺少身份一致证据的可选章节'],
    lesson: {
      id: 'lesson-1', status: 'DRAFT_READY', sections: [{
        position: 1, title: '已发布章节', visualCaption: '', steps: [{
          position: 1, heading: '先执行动作', kind: 'DO', text: '按规则书执行。', sourcePages: [2], visualFocus: null,
        }],
      }],
    },
  }
}

function failedAnswer(status: 'INVALID_MODEL_OUTPUT' | 'MODEL_TIMEOUT') {
  return {
    answer: {
      status,
      shortVerdict: status === 'INVALID_MODEL_OUTPUT' ? '结构化结果未发布。' : '本轮超时停止。',
      explanation: null, citations: [], exceptions: [], confidence: 'LOW', answerBasis: null,
      clarification: null, warnings: [],
    },
    visualAids: [], examples: [],
  }
}

async function mountAt(path: string, post: () => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input)
    if (url.includes('/api/auth/session')) return new Response(null, { status: 401 })
    if (url.includes('/answers') && init?.method === 'POST') return post()
    return Response.json(lessonPayload())
  }))
  const router = await routerFor(path)
  const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('PublicLessonView failure visibility', () => {
  afterEach(() => {
    setLocale('zh-CN')
    localStorage.clear()
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  it('keeps published chapters and names unresolved topics as local degradation', async () => {
    const wrapper = await mountAt('/read/plan-1', () => Response.json({}))

    expect(wrapper.text()).toContain('已发布章节')
    const unresolved = wrapper.get('[data-testid="public-lesson-unresolved-topics"]')
    expect(unresolved.text()).toContain('局部未完成主题')
    expect(unresolved.text()).toContain('缺少身份一致证据的可选章节')
  })

  it('shows a cited conclusion alongside the clarification needed for the remaining situation', async () => {
    const answer = {
      status: 'ANSWERED', shortVerdict: '先完成结算，再记录本轮结果。',
      explanation: '规则书将记录安排在结算之后。',
      citations: [{ heading: '回合结束', excerpt: '结算后记录结果。', pageFrom: 4, pageTo: 4 }],
      exceptions: [], confidence: 'MEDIUM', answerBasis: 'DIRECT_RULE',
      clarification: '你提到的是哪张卡牌的效果？', warnings: [],
    }
    const wrapper = await mountAt('/read/plan-1/questions', () => Response.json({
      answer, visualAids: [], examples: [],
    }))
    await wrapper.get('#public-question').setValue('什么时候结算？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(answer.shortVerdict)
    expect(wrapper.text()).toContain(answer.clarification)
    expect(wrapper.text()).toContain('回合结束')
    expect(wrapper.get('[data-confidence]').attributes('data-confidence')).toBe('MEDIUM')
  })

  it('does not tell the player to rewrite a question after invalid model JSON', async () => {
    const wrapper = await mountAt('/read/plan-1/questions', () => Response.json(failedAnswer('INVALID_MODEL_OUTPUT')))
    await wrapper.get('#public-question').setValue('什么时候结算？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('internal-correction')
    expect(details.text()).toContain('INVALID_MODEL_OUTPUT')
    expect(wrapper.text()).toContain('问题本身没有被拒绝')
    expect(wrapper.text()).not.toMatch(/改写问题|rephrase/i)
  })

  it('shows a service transport stop as retry-preserved with its backend code', async () => {
    const wrapper = await mountAt('/read/plan-1/questions', () => new Response(null, { status: 503 }))
    await wrapper.get('#public-question').setValue('什么时候结算？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('retry-preserved')
    expect(details.text()).toContain('answer_service_unavailable')
    expect(wrapper.find('[data-testid="public-answer-failure-retry-unchanged"]').exists()).toBe(true)
  })
})
