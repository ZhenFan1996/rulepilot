import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonAnswerPanel from './LessonAnswerPanel.vue'

const answered = {
  status: 'ANSWERED' as const,
  shortVerdict: '先完成结算，再记录本轮结果。',
  explanation: '规则书把结算放在本轮结束之后。',
  citations: [{ chunkId: 'chunk-1', sectionType: 'RULE', heading: '回合结束', excerpt: '结算本轮。', pageFrom: 4, pageTo: 4 }],
  exceptions: ['除非效果明确打断结算。'],
  confidence: 'HIGH' as const,
  official: false,
  confirmedRulingId: null,
  confirmedRulingVersion: null,
  clarification: null,
}

const baseProps = {
  currentSection: { position: 2, title: '完成本轮' },
  question: '',
  answer: null,
  answeredQuestion: '',
  answerTurns: [],
  activeLearningIntent: null,
  answerLoading: false,
  answerError: '',
  online: true,
  ruling: null,
  rulingSaving: false,
  rulingError: '',
  rulingConflict: false,
  editingRuling: false,
  editedVerdict: '',
  editedExplanation: '',
}

describe('LessonAnswerPanel', () => {
  it('keeps the question entry surface event-driven for the lesson reader', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: baseProps,
      global: {
        stubs: {
          VoiceQuestionCapture: { template: '<button type="button" @click="$emit(\'transcript\', \'语音问题\')">语音输入</button>' },
        },
      },
    })

    await wrapper.get('#lesson-question').setValue('我什么时候结算？')
    expect(wrapper.emitted('update:question')).toEqual([['我什么时候结算？']])

    await wrapper.get('form').trigger('submit')
    await wrapper.findAll('button').find((button) => button.text() === '走个例子')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '拍照识别卡牌文字')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '语音输入')!.trigger('click')

    expect(wrapper.emitted('ask')).toHaveLength(1)
    expect(wrapper.emitted('requestHelp')).toEqual([['EXAMPLE']])
    expect(wrapper.emitted('openCardOcr')).toHaveLength(1)
    expect(wrapper.emitted('voiceTranscript')).toEqual([['语音问题']])
  })

  it('renders cited answers and forwards confirmed-ruling editing without owning the write', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '什么时候结算？',
        answeredQuestion: '什么时候结算？',
        answer: answered,
        answerTurns: [{ question: '什么时候结算？', answer: answered, learningIntent: null }],
        ruling: {
          id: 'ruling-1', shortVerdict: answered.shortVerdict, explanation: answered.explanation,
          citations: answered.citations, exceptions: answered.exceptions, confidence: 'HIGH', status: 'CONFIRMED', version: 3,
        },
      },
    })

    expect(wrapper.text()).toContain('先完成结算，再记录本轮结果。')
    expect(wrapper.text()).toContain('回合结束')
    expect(wrapper.text()).toContain('第 4 页')

    await wrapper.findAll('button').find((button) => button.text() === '编辑裁定')!.trigger('click')
    expect(wrapper.emitted('update:editing-ruling')).toEqual([[true]])

    await wrapper.setProps({ editingRuling: true })
    await wrapper.get('#ruling-verdict').setValue('更新后的裁定')
    await wrapper.get('#ruling-explanation').setValue('更新后的解释')
    await wrapper.setProps({ editedVerdict: '更新后的裁定', editedExplanation: '更新后的解释' })
    await wrapper.findAll('button').find((button) => button.text() === '保存修改')!.trigger('click')

    expect(wrapper.emitted('update:edited-verdict')).toEqual([['更新后的裁定']])
    expect(wrapper.emitted('update:edited-explanation')).toEqual([['更新后的解释']])
    expect(wrapper.emitted('saveRulingRevision')).toHaveLength(1)
  })
})
