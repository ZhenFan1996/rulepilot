import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { setLocale } from '@/lib/locale'
import LessonAnswerPanel from './LessonAnswerPanel.vue'

const answered = {
  language: 'zh-CN' as const,
  status: 'ANSWERED' as const,
  shortVerdict: '先完成结算，再记录本轮结果。',
  explanation: '规则书把结算放在本轮结束之后。',
  citations: [{ heading: '回合结束', excerpt: '结算本轮。', pageFrom: 4, pageTo: 4 }],
  exceptions: ['除非效果明确打断结算。'],
  confidence: 'HIGH' as const,
  answerBasis: 'GROUNDED_APPLICATION' as const,
  source: 'UPLOADED' as const,
  clarification: null,
  recovery: null,
  warnings: [],
}

const baseProps = {
  question: '', answer: null, answeredQuestion: '', answerTurns: [], activeLearningIntent: null,
  answerLoading: false, answerError: '', answerOutcome: 'none' as const, online: true,
  ruling: null, rulingSaving: false, rulingError: '', rulingConflict: false,
  editingRuling: false, editedVerdict: '', editedExplanation: '',
}

const global = { stubs: { VoiceQuestionCapture: true } }

describe('LessonAnswerPanel reliability boundary', () => {
  afterEach(() => setLocale('zh-CN'))

  it('keeps the question entry event-driven', async () => {
    const wrapper = mount(LessonAnswerPanel, { props: baseProps, global })
    await wrapper.get('#lesson-question').setValue('我什么时候结算？')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('update:question')).toEqual([['我什么时候结算？']])
    expect(wrapper.emitted('ask')).toHaveLength(1)
  })

  it('keeps the supported answer and its unresolved clarification visible while a follow-up can be cancelled', async () => {
    const partial = {
      ...answered,
      confidence: 'MEDIUM' as const,
      clarification: '这次触发的是哪张卡牌的效果？',
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '新的问题',
        answer: partial,
        answeredQuestion: '什么时候结算？',
        answerTurns: [{ question: '什么时候结算？', answer: partial, learningIntent: null }],
        answerLoading: true,
      },
      global,
    })

    expect(wrapper.text()).toContain(answered.shortVerdict)
    expect(wrapper.text()).toContain('回合结束')
    expect(wrapper.text()).toContain(partial.clarification)
    expect(wrapper.get('[data-confidence]').attributes('data-confidence')).toBe('MEDIUM')
    await wrapper.findAll('button').find(button => button.text() === '停止等待')!.trigger('click')
    expect(wrapper.emitted('cancelAnswer')).toHaveLength(1)
    await wrapper.setProps({ answer: null, answerLoading: false })
    const history = wrapper.get('details')
    expect(history.text()).toContain(partial.explanation)
    expect(history.text()).toContain(partial.clarification)
  })

  it('shows a retry-preserved transport stop with its real code and allows unchanged retry', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '什么时候结算？',
        answerError: '本轮超时停止。',
        answerOutcome: 'failed',
        answerFailureRecovery: {
          code: 'answer_timeout', message: '本轮超时停止。', actionLabel: '原样重试', draft: '',
          canRetryUnchanged: true,
        },
      },
      global,
    })

    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('retry-preserved')
    expect(details.text()).toContain('answer_timeout')
    expect(details.text()).toContain('答疑 Agent')
    await wrapper.get('[data-testid="answer-failure-retry-unchanged"]').trigger('click')
    expect(wrapper.emitted('ask')).toHaveLength(1)
  })

  it('presents invalid model output as internal correction without asking the player to rephrase', () => {
    const invalid = {
      ...answered,
      status: 'INVALID_MODEL_OUTPUT' as const,
      shortVerdict: '结构化结果没有通过发布边界。',
      explanation: '', citations: [], exceptions: [], confidence: 'LOW' as const,
      recovery: {
        message: '内部修正未能发布结果。', actionLabel: '原样重试问题', draft: '', canRetryUnchanged: true,
      },
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '什么时候结算？', answeredQuestion: '什么时候结算？', answer: invalid,
        answerTurns: [{ question: '什么时候结算？', answer: invalid, learningIntent: null }],
      },
      global,
    })

    const details = wrapper.get('[data-testid="player-failure-details"]')
    expect(details.attributes('data-failure-classification')).toBe('internal-correction')
    expect(details.text()).toContain('INVALID_MODEL_OUTPUT')
    expect(wrapper.text()).toContain('问题没有被拒绝')
    expect(wrapper.text()).not.toMatch(/改写问题|rephrase/i)
  })
})
