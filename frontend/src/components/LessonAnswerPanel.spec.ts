import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonAnswerPanel from './LessonAnswerPanel.vue'
import { setLocale } from '@/lib/locale'

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
  question: '',
  answer: null,
  answeredQuestion: '',
  answerTurns: [],
  activeLearningIntent: null,
  answerLoading: false,
  answerError: '',
  answerOutcome: 'none' as const,
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
  afterEach(() => setLocale('zh-CN'))

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
    await wrapper.findAll('button').find((button) => button.text() === '拍照识别卡牌文字')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '语音输入')!.trigger('click')

    expect(wrapper.emitted('ask')).toHaveLength(1)
    expect(wrapper.get('form').element.parentElement?.parentElement?.className).toContain('max-w-3xl')
    expect(wrapper.find('.border-dashed').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === '走个例子')).toBe(false)
    expect(wrapper.emitted('openCardOcr')).toHaveLength(1)
    expect(wrapper.emitted('voiceTranscript')).toEqual([['语音问题']])
  })

  it('shows a readable legacy visual citation without its internal evidence instructions', () => {
    const visualAnswer = {
      ...answered,
      citations: [{
        heading: '目标计分',
        pageFrom: 4,
        pageTo: 4,
        excerpt: 'Visual-transcribed rule evidence. Only the statements under Visible rule facts are rule evidence. '
          + 'Do not derive a per-item value from a worked total.\nVisible rule facts: 每张完成的目标卡得 2 分。',
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: visualAnswer,
        answeredQuestion: '目标卡怎么计分？',
        answerTurns: [{ question: '目标卡怎么计分？', answer: visualAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('每张完成的目标卡得 2 分。')
    expect(wrapper.text()).not.toMatch(/Visual-transcribed|Do not derive|Visible rule facts/)
  })

  it('disables thread reset while a ruling edit or save makes clearing unsafe', () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: answered,
        answerTurns: [{ question: '什么时候结算？', answer: answered, learningIntent: null }],
        clearThreadDisabled: true,
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.findAll('button').find(button => button.text() === '清空本次答疑')!.attributes('disabled'))
      .toBeDefined()
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
          citations: answered.citations.map(citation => ({
            ...citation, chunkId: 'chunk-1', sectionType: 'RULE',
          })),
          exceptions: answered.exceptions, confidence: 'HIGH', status: 'CONFIRMED', version: 3,
        },
      },
    })

    expect(wrapper.text()).toContain('先完成结算，再记录本轮结果。')
    expect(wrapper.text()).toContain('回合结束')
    expect(wrapper.text()).toContain('第 4 页')
    expect(wrapper.text()).toContain('按规则回答当前问题')
    expect(wrapper.text()).toContain('规则书把结算放在本轮结束之后')
    expect(wrapper.text()).not.toContain('这条答案如何得出')
    expect(wrapper.text()).not.toContain('这不是额外规则')
    expect(wrapper.text()).toContain('直接核对规则依据')
    expect(wrapper.get('form').element.parentElement?.className).toContain('lg:sticky')
    expect(wrapper.get('article[aria-live="polite"]').element.parentElement?.className).toContain('min-w-0')

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

  it('preserves the model-authored explanation and complete procedure without lexical suppression', () => {
    const repetitiveAnswer = {
      ...answered,
      shortVerdict: '轮到你时，打出一张人格牌，然后执行该牌行动。',
      explanation: '先选择并打出人格牌，再执行这张牌的行动。',
      walkthroughSteps: [
        { instruction: '打出一张人格牌。', explanation: '轮到你时选择并打出一张人格牌。', orderBasis: 'RULE_ORDER' as const },
        { instruction: '执行该牌行动。', explanation: '打出后执行这张牌的行动。', orderBasis: 'RULE_ORDER' as const },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: repetitiveAnswer,
        answeredQuestion: '基本回合怎么走？',
        answerTurns: [{ question: '基本回合怎么走？', answer: repetitiveAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain(repetitiveAnswer.shortVerdict)
    expect(wrapper.text()).toContain(repetitiveAnswer.explanation)
    expect(wrapper.text()).toContain('照这个顺序做')
    expect(wrapper.text()).toContain('直接核对规则依据')
  })

  it('keeps a non-repeated walkthrough and every material condition visible', () => {
    const proceduralAnswer = {
      ...answered,
      shortVerdict: '执行 Architect 行动。',
      explanation: '移动后可以建造房屋。',
      walkthroughSteps: [
        { instruction: '先移动殖民者。', explanation: '移动总步数不能超过殖民者数量。', orderBasis: 'RULE_ORDER' as const },
        { instruction: '再建造房屋。', explanation: '每座房屋都要支付对应费用。', orderBasis: 'RULE_ORDER' as const },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: proceduralAnswer,
        answeredQuestion: 'Architect 具体怎么执行？',
        answerTurns: [{ question: 'Architect 具体怎么执行？', answer: proceduralAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('移动后可以建造房屋')
    expect(wrapper.text()).toContain('照这个顺序做')
    expect(wrapper.text()).toContain('移动总步数不能超过殖民者数量')
    expect(wrapper.text()).toContain('每座房屋都要支付对应费用')
  })

  it('lets the player challenge a conclusion through a fresh verified retrieval', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '什么时候结算？',
        answeredQuestion: '什么时候结算？',
        answer: answered,
        answerTurns: [{ question: '什么时候结算？', answer: answered, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('这次回答解决问题了吗？')
    expect(wrapper.text()).toContain('不会被当成规则依据')
    await wrapper.findAll('button').find(button => button.text() === '可能有误')!.trigger('click')

    expect(wrapper.emitted('requestHelp')).toContainEqual(['VERIFY'])
  })

  it('shows deterministic arithmetic separately from the cited rule explanation', () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answeredQuestion: '我有8个资源，可以得多少分？',
        answer: { ...answered, calculations: [{ expression: 'floor(8 / 3) * 5', result: '10' }] },
        answerTurns: [],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('规则计算')
    expect(wrapper.text()).toContain('floor(8 / 3) * 5 = 10')
    expect(wrapper.text()).toContain('下方计算过程便于直接复核结果')
  })

  it('shows cited term meanings and their confusion boundaries', () => {
    const definitionAnswer = {
      ...answered,
      termDefinitions: [{
        term: '控制',
        definition: '你在该区域的棋子数量严格多于每一位对手。',
        boundary: '平局不算控制。',
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: definitionAnswer,
        answeredQuestion: '控制是什么意思？',
        answerTurns: [{ question: '控制是什么意思？', answer: definitionAnswer, learningIntent: 'DEFINE' }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('术语定义')
    expect(wrapper.text()).toContain('你在该区域的棋子数量严格多于每一位对手')
    expect(wrapper.text()).toContain('适用边界：平局不算控制')
  })

  it('shows a cited worked example as setup, action, and outcome', () => {
    const exampleAnswer = {
      ...answered,
      workedExamples: [{
        setup: '卡牌基础值为 1，并受到 -4 修正。',
        action: '把 -4 修正应用到基础值。',
        outcome: '最终数值为 -3。',
        basis: 'RULEBOOK_EXAMPLE' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: exampleAnswer,
        answeredQuestion: '负修正怎么算？',
        answerTurns: [{ question: '负修正怎么算？', answer: exampleAnswer, learningIntent: 'EXAMPLE' }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('带出处的示例')
    expect(wrapper.text()).toContain('规则书原例')
    expect(wrapper.text()).toContain('局面：卡牌基础值为 1，并受到 -4 修正。')
    expect(wrapper.text()).toContain('动作：把 -4 修正应用到基础值。')
    expect(wrapper.text()).toContain('结果：最终数值为 -3。')
  })

  it('shows why one rule takes priority without hiding the competing rule', () => {
    const priorityAnswer = {
      ...answered,
      priorityResolutions: [{
        baseRule: '通则禁止执行该动作。',
        competingRule: '卡牌效果允许执行该动作。',
        resolution: '规则书明确规定卡牌效果覆盖通则，因此本次采用卡牌效果。',
        basis: 'EXPLICIT_OVERRIDE' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: priorityAnswer,
        answeredQuestion: '卡牌和通则冲突时听谁的？',
        answerTurns: [{ question: '卡牌和通则冲突时听谁的？', answer: priorityAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('规则冲突怎么处理')
    expect(wrapper.text()).toContain('明确覆盖')
    expect(wrapper.text()).toContain('原规则：通则禁止执行该动作。')
    expect(wrapper.text()).toContain('竞争规则：卡牌效果允许执行该动作。')
    expect(wrapper.text()).toContain('实际采用：规则书明确规定卡牌效果覆盖通则')
  })

  it('shows the simultaneous timing context, exact order, and who sets it', () => {
    const timingAnswer = {
      ...answered,
      timingResolutions: [{
        timingContext: '两个效果在当前玩家的回合中同时发生。',
        resolutionOrder: '按当前玩家选择的先后顺序逐个结算。',
        orderSource: '正在进行回合的玩家。',
        basis: 'CURRENT_PLAYER_CHOOSES' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: timingAnswer,
        answeredQuestion: '两个效果同时发生时谁决定顺序？',
        answerTurns: [{ question: '两个效果同时发生时谁决定顺序？', answer: timingAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('同时发生时怎么排顺序')
    expect(wrapper.text()).toContain('当前玩家选择')
    expect(wrapper.text()).toContain('发生情境：两个效果在当前玩家的回合中同时发生。')
    expect(wrapper.text()).toContain('结算顺序：按当前玩家选择的先后顺序逐个结算。')
    expect(wrapper.text()).toContain('顺序来源：正在进行回合的玩家。')
  })

  it('shows every tie-break step and the exact still-tied outcome', () => {
    const tieAnswer = {
      ...answered,
      tieResolutions: [{
        tieContext: '两名玩家宝藏数量相同。',
        resolutionSteps: ['先比较宝藏难度总和。', '仍平则比较英雄费用总和。', '仍平则比较金币。'],
        finalOutcome: '仍然平局时，共享胜利。',
        basis: 'ORDERED_TIEBREAKERS' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: tieAnswer,
        answeredQuestion: '平局怎么判？',
        answerTurns: [{ question: '平局怎么判？', answer: tieAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('平局判定')
    expect(wrapper.text()).toContain('逐级破平')
    expect(wrapper.text()).toContain('先比较宝藏难度总和。')
    expect(wrapper.text()).toContain('仍平则比较英雄费用总和。')
    expect(wrapper.text()).toContain('最终结果：仍然平局时，共享胜利。')
  })

  it('shows the cited rule condition beside the player setup and applicability result', () => {
    const scopeAnswer = {
      ...answered,
      answerBasis: 'GROUNDED_APPLICATION' as const,
      scopeResolutions: [{
        ruleContext: '两人局的统治卡。',
        governingCondition: '两名玩家时不使用统治卡。',
        currentSituation: '当前是两人局。',
        matchStatus: 'MATCHES_SCOPE' as const,
        effect: '本局不使用统治卡。',
        basis: 'PLAYER_COUNT' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: scopeAnswer,
        answeredQuestion: '两人局能用统治卡吗？',
        answerTurns: [{ question: '两人局能用统治卡吗？', answer: scopeAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('这条规则适用于当前局吗')
    expect(wrapper.text()).toContain('命中适用条件')
    expect(wrapper.text()).toContain('规则条件：两名玩家时不使用统治卡。')
    expect(wrapper.text()).toContain('当前局面：当前是两人局。')
    expect(wrapper.text()).toContain('实际效果：本局不使用统治卡。')
  })

  it('shows explicit and missing situation facts and prepares a bounded follow-up', async () => {
    const situationAnswer = {
      ...answered,
      answerBasis: 'GROUNDED_APPLICATION' as const,
      situationChecks: [
        { requirement: '前置条件必须完成', status: 'CONFIRMED' as const, playerFact: '我已经完成前置条件' },
        { requirement: '行动窗口仍然开放', status: 'NOT_PROVIDED' as const, playerFact: '' },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: { ...baseProps, answeredQuestion: '现在可以结算吗？', answer: situationAnswer, answerTurns: [] },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('当前局面条件')
    expect(wrapper.text()).toContain('已确认')
    expect(wrapper.text()).toContain('尚未提供')
    await wrapper.findAll('button').find(button => button.text() === '补充这个条件')!.trigger('click')
    expect(wrapper.emitted('update:question')).toContainEqual(['补充“行动窗口仍然开放”：'])
  })

  it('shows cited walkthrough steps without confusing teaching order with rule order', () => {
    const walkthroughAnswer = {
      ...answered,
      walkthroughSteps: [
        { instruction: '支付费用。', explanation: '规则要求先完成支付。', orderBasis: 'RULE_ORDER' as const },
        { instruction: '认识两种结果。', explanation: '这里拆成一步只是为了讲清楚。', orderBasis: 'EXPLANATION_ORDER' as const },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: { ...baseProps, answeredQuestion: '具体步骤是什么？', answer: walkthroughAnswer, answerTurns: [] },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('照这个顺序做')
    expect(wrapper.text()).toContain('支付费用。')
    expect(wrapper.text()).toContain('规则规定顺序')
    expect(wrapper.text()).toContain('讲解拆分')
  })

  it('shows separately sourced outcomes without promoting a rulebook example', () => {
    const decisionAnswer = {
      ...answered,
      decisionBranches: [
        { condition: '供应区有对应物品。', outcome: '拿取该物品。', basis: 'EXPLICIT_RULE' as const },
        { condition: '规则书示例中的蓝色玩家。', outcome: '等待下一步。', basis: 'RULEBOOK_EXAMPLE' as const },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: { ...baseProps, answeredQuestion: '不同情况下分别会怎样？', answer: decisionAnswer, answerTurns: [] },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('不同条件会发生什么')
    expect(wrapper.text()).toContain('规则明示')
    expect(wrapper.text()).toContain('规则书示例')
    expect(wrapper.text()).toContain('不会被当成通用规则')
  })

  it('shows each cited exception as a condition and practical effect', () => {
    const exceptionAnswer = {
      ...answered,
      exceptions: [],
      exceptionClauses: [
        { condition: '供应区没有对应物品。', effect: '不能制作这张牌。' },
        { condition: '你已有同名持续效果。', effect: '不能再制作一个。' },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: { ...baseProps, answeredQuestion: '有哪些例外和限制？', answer: exceptionAnswer, answerTurns: [] },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('例外和限制')
    expect(wrapper.text()).toContain('供应区没有对应物品')
    expect(wrapper.text()).toContain('不能制作这张牌')
    expect(wrapper.text()).toContain('适用条件、实际影响和规则出处')
  })

  it('offers a grounded terminology follow-up only after a cited answer exists', async () => {
    const empty = mount(LessonAnswerPanel, {
      props: baseProps,
      global: { stubs: { VoiceQuestionCapture: true } },
    })
    expect(empty.findAll('button').some(button => button.text() === '解释关键词')).toBe(false)

    const answeredWrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: answered,
        answeredQuestion: '里程碑什么时候结算？',
        answerTurns: [{ question: '里程碑什么时候结算？', answer: answered, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })
    await answeredWrapper.findAll('button').find(button => button.text() === '解释关键词')!.trigger('click')
    await answeredWrapper.findAll('button').find(button => button.text() === '看原文依据')!.trigger('click')

    expect(answeredWrapper.emitted('requestHelp')).toContainEqual(['DEFINE'])
    expect(answeredWrapper.emitted('requestHelp')).toContainEqual(['SOURCE'])

    await answeredWrapper.setProps({ online: false })
    for (const label of ['解释关键词', '前后怎么接', '走个例子', '例外和限制', '看原文依据']) {
      expect(answeredWrapper.findAll('button').find(button => button.text() === label)?.attributes('disabled')).toBeDefined()
    }
  })

  it('turns answer feedback into an immediate next step without claiming it was persisted', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '',
        answeredQuestion: '什么时候结算？',
        answer: answered,
        answerTurns: [{ question: '什么时候结算？', answer: answered, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    await wrapper.findAll('button').find(button => button.text() === '没讲清楚')!.trigger('click')
    expect(wrapper.emitted('requestHelp')).toContainEqual(['SIMPLIFY'])

    await wrapper.findAll('button').find(button => button.text() === '解决了')!.trigger('click')
    expect(wrapper.text()).toContain('这次答疑到这里即可')
    expect(wrapper.findAll('button').some(button => button.text() === '解决了')).toBe(false)
  })

  it('shows one honest answer status without presenting placeholder implementation progress', () => {
    const waiting = mount(LessonAnswerPanel, {
      props: { ...baseProps, answerLoading: true },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    const waitingStatus = waiting.get('[data-testid="player-work-status"]')
    expect(waitingStatus.text()).toBe('正在核对回答')
    expect(waitingStatus.attributes('data-player-work-terminality')).toBe('active')
    expect(waiting.text()).toContain('问题已收到，正在等待这次答疑的最新进度')
    expect(waiting.text()).not.toContain('对齐问题与规则书术语')
    expect(waiting.text()).not.toContain('查找规则书原文')

    const observed = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answerLoading: true,
        activeLearningIntent: 'VERIFY',
        agentTrace: [{ sequence: 2, kind: 'tool', label: '重新读取引用页', status: 'running' }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(observed.text()).toContain('正在处理“重新查规则并核对”请求')
    expect(observed.text()).toContain('重新读取引用页')
    expect(observed.text()).not.toContain('等待这次答疑的最新进度')

    setLocale('en')
    const english = mount(LessonAnswerPanel, {
      props: { ...baseProps, answerLoading: true },
      global: { stubs: { VoiceQuestionCapture: true } },
    })
    expect(english.get('[data-testid="player-work-status"]').text()).toBe('Checking answer')
    expect(english.text()).toContain('Question received. Waiting for the latest verified progress')
  })

  it('keeps prior cited answers visible and lets the player cancel a slow answer', async () => {
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '那计分时呢？',
        answerLoading: true,
        answerTurns: [{ question: '什么时候结算？', answer: answered, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('什么时候结算？')
    expect(wrapper.text()).toContain('先完成结算，再记录本轮结果。')
    await wrapper.findAll('button').find(button => button.text() === '停止等待')!.trigger('click')

    expect(wrapper.emitted('cancelAnswer')).toHaveLength(1)

    await wrapper.setProps({
      answerLoading: false,
      answerError: '已停止等待；未完成结果不会替换当前页面。',
      answerOutcome: 'cancelled',
    })
    const stopped = wrapper.get('[data-testid="player-work-status"]')
    expect(stopped.text()).toBe('已取消')
    expect(stopped.attributes('data-player-work-outcome')).toBe('cancelled')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('marks the soft budget without presenting the unfinished answer as a result', async () => {
    const prior = {
      ...answered,
      citations: [{
        heading: '结算时机', excerpt: '先完成本次结算。', pageFrom: 4, pageTo: 4,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '如果结算被打断呢？',
        answerLoading: true,
        answerElapsedSeconds: 8,
        answerSoftBudgetReached: true,
        answerTurns: [{ question: '什么时候结算？', answer: prior, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('上一条已核对答案和引用仍保留在上方')
    expect(wrapper.text()).toContain('当前问题还在核对原文与结论')
    expect(wrapper.text()).toContain('8 秒')
    expect(wrapper.text()).not.toContain('如果结算被打断呢？：先完成结算')

    await wrapper.setProps({ question: 'What if scoring is interrupted?' })
    expect(wrapper.get('[data-testid="answer-soft-budget"]').text())
      .toContain('The previous verified answer and citations remain above')
    expect(wrapper.get('[data-testid="answer-soft-budget"]').text()).toContain('8 seconds')
  })

  it('localizes the personal answer thread when the player selects English', () => {
    setLocale('en')
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: answered,
        answeredQuestion: 'What are the exceptions?',
        answerTurns: [
          { question: 'When do I resolve this?', answer: answered, learningIntent: null },
          { question: 'What are the exceptions?', answer: answered, learningIntent: 'EXCEPTIONS' },
        ],
      },
      global: {
        stubs: { VoiceQuestionCapture: true },
      },
    })

    expect(wrapper.text()).toContain('Ask the rulebook')
    expect(wrapper.text()).toContain('Ask a question')
    expect(wrapper.text()).not.toContain('How this answer was reached')
    expect(wrapper.text()).toContain('Applied to your question')
    expect(wrapper.text()).toContain('Page 4')
    expect(wrapper.text()).toContain('Review this answer and its sources')
    expect(wrapper.text()).toContain('Rulebook sources used for this answer')
    expect(wrapper.text()).toContain('Did this answer resolve your question?')
    expect(wrapper.text()).toContain('Your choice is never treated as rule evidence.')
    expect(wrapper.text()).toContain('Still unclear')
    expect(wrapper.text()).toContain('May be wrong')
    expect(wrapper.text()).toContain('Check the rule support directly')
  })

  it('shows the first rule source immediately and distinguishes medium confidence from high', () => {
    const secondCitation = {
      heading: '计分顺序',
      excerpt: '完成目标后计算分数。', pageFrom: 7, pageTo: 8,
    }
    const mediumAnswer = {
      ...answered,
      confidence: 'MEDIUM' as const,
      citations: [...answered.citations, secondCitation],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: mediumAnswer,
        answeredQuestion: '什么时候计分？',
        answerTurns: [{ question: '什么时候计分？', answer: mediumAnswer, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    const confidence = wrapper.get('[data-confidence="MEDIUM"]')
    expect(confidence.text()).toBe('已核对依据')
    expect(confidence.classes()).toContain('bg-amber-50')
    expect(confidence.classes()).not.toContain('bg-emerald-50')

    const evidence = wrapper.get('[aria-labelledby="lesson-answer-evidence-title"]')
    expect(evidence.get('article').text()).toContain('回合结束')
    expect(evidence.get('article').text()).toContain('第 4 页')
    expect(evidence.get('article').text()).toContain('结算本轮。')
    expect(evidence.get('summary').text()).toBe('查看另外 1 条出处')
    expect(evidence.get('details').text()).toContain('计分顺序')
    expect(evidence.get('details').text()).toContain('第 7–8 页')
  })

  it('shows a specific qualification while keeping an evidence-scoped answer readable', () => {
    const warned = {
      ...answered,
      status: 'ANSWERED_WITH_WARNING' as const,
      confidence: 'LOW' as const,
      warnings: [{ type: 'LOW_CONFIDENCE' as const }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: warned,
        answeredQuestion: '什么时候结算？',
        answerTurns: [{ question: '什么时候结算？', answer: warned, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('先完成结算')
    expect(wrapper.text()).toContain('这条结论的依据还不够稳妥')
    expect(wrapper.text()).not.toContain('模型')
    expect(wrapper.text()).toContain('回合结束')
  })

  it('turns insufficient evidence into an actionable refinement without exposing an internal run id', async () => {
    const insufficient = {
      ...answered,
      status: 'INSUFFICIENT_EVIDENCE' as const,
      shortVerdict: '当前无法可靠回答。',
      explanation: '',
      citations: [],
      exceptions: [],
      confidence: 'LOW' as const,
      recovery: {
        message: '请补充规则中的具体对象名称、发生时机或页码。',
        actionLabel: '回到问题补充信息',
        draft: '',
      },
    }
    const wrapper = mount(LessonAnswerPanel, {
      attachTo: document.body,
      props: {
        ...baseProps,
        question: '这个效果什么时候发生？',
        answer: insufficient,
        answeredQuestion: '这个效果什么时候发生？',
        answerTurns: [{ question: '这个效果什么时候发生？', answer: insufficient, learningIntent: null }],
        agentTrace: [{ sequence: 1, kind: 'verification', label: '没有找到足够依据', status: 'stopped' }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('规则中的具体对象名称')
    expect(wrapper.text()).not.toContain('本次回答编号')
    expect(wrapper.text()).not.toContain('11111111-1111-4111-8111-111111111111')
    expect(wrapper.text()).not.toContain('没有找到足够依据')
    expect(wrapper.find('[data-confidence]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('上传的规则书')

    await wrapper.findAll('button').find(button => button.text() === '回到问题补充信息')!.trigger('click')
    expect(document.activeElement).toBe(wrapper.get('#lesson-question').element)
    wrapper.unmount()
  })

  it('uses the current-turn recovery language even when the surrounding UI is Chinese', async () => {
    setLocale('zh-CN')
    const failure = {
      ...answered,
      language: 'en' as const,
      status: 'MODEL_TIMEOUT' as const,
      shortVerdict: "I couldn't finish checking the rule in time.",
      explanation: '',
      citations: [],
      exceptions: [],
      confidence: 'LOW' as const,
      recovery: {
        message: 'Your question is still here. Review or edit it, then try again.',
        actionLabel: 'Review and try again',
        draft: 'When does the cobalt spindle resolve?',
      },
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: 'When does the cobalt spindle resolve?',
        answer: failure,
        answeredQuestion: 'When does the cobalt spindle resolve?',
        answerTurns: [{
          question: 'When does the cobalt spindle resolve?', answer: failure, learningIntent: null,
        }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('Your question is still here')
    expect(wrapper.text()).toContain('Review and try again')
    expect(wrapper.text()).not.toContain('这次没有在时限内')
    expect(wrapper.find('[data-confidence]').exists()).toBe(false)

    await wrapper.findAll('button').find(button => button.text() === 'Review and try again')!.trigger('click')

    expect(wrapper.emitted('update:question')).toEqual([['When does the cobalt spindle resolve?']])
    expect(wrapper.emitted('ask')).toBeUndefined()
  })

  it('keeps a localized clarification actionable without publishing a conclusion', async () => {
    const clarification = {
      ...answered,
      status: 'CLARIFICATION_REQUIRED' as const,
      shortVerdict: '需要补充一项信息后才能查证规则。',
      explanation: '问题中有无法安全确定的指代。',
      citations: [],
      exceptions: [],
      confidence: 'LOW' as const,
      clarification: '你说的“这个”具体指什么？请写出规则书里的名称。',
      recovery: {
        message: '你说的“这个”具体指什么？请写出规则书里的名称。',
        actionLabel: '补充这项信息',
        draft: '我指的是：',
      },
    }
    const wrapper = mount(LessonAnswerPanel, {
      attachTo: document.body,
      props: {
        ...baseProps,
        question: '这个什么时候触发？',
        answer: clarification,
        answeredQuestion: '这个什么时候触发？',
        answerTurns: [{ question: '这个什么时候触发？', answer: clarification, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('你说的“这个”具体指什么')
    expect(wrapper.text()).not.toContain('这条答案如何得出')
    await wrapper.findAll('button').find(button => button.text() === '补充这项信息')!.trigger('click')
    expect(wrapper.emitted('update:question')).toContainEqual(['我指的是：'])
    expect(document.activeElement).toBe(wrapper.get('#lesson-question').element)
    wrapper.unmount()
  })

  it('keeps an in-progress clarification reply instead of replacing it', async () => {
    const clarification = {
      ...answered,
      status: 'CLARIFICATION_REQUIRED' as const,
      shortVerdict: '需要补充一项信息后才能查证规则。',
      explanation: '问题中有无法安全确定的指代。',
      citations: [],
      exceptions: [],
      confidence: 'LOW' as const,
      clarification: '你说的“这个”具体指什么？',
      recovery: {
        message: '你说的“这个”具体指什么？',
        actionLabel: '补充这项信息',
        draft: '我指的是：',
      },
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        question: '我指的是：红色行动牌',
        answer: clarification,
        answeredQuestion: '这个什么时候触发？',
        answerTurns: [{ question: '这个什么时候触发？', answer: clarification, learningIntent: null }],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    await wrapper.findAll('button').find(button => button.text() === '补充这项信息')!.trigger('click')

    expect(wrapper.emitted('update:question')).toBeUndefined()
  })

  it('keeps earlier explanations, exceptions, warnings, and citations available after a follow-up', () => {
    const earlier = {
      ...answered,
      status: 'ANSWERED_WITH_WARNING' as const,
      explanation: '先检查行动是否已经结算，再处理本轮记录。',
      exceptions: ['被明确写为即时效果时，不等待回合结束。'],
      warnings: [{ type: 'INDIRECT_CITATION' as const }],
    }
    const latest = {
      ...answered,
      shortVerdict: '例外只适用于明确标为即时的效果。',
      explanation: '其他效果仍遵循通常结算顺序。',
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: latest,
        answeredQuestion: '那有什么例外？',
        answerTurns: [
          { question: '什么时候结算？', answer: earlier, learningIntent: null },
          { question: '那有什么例外？', answer: latest, learningIntent: 'EXCEPTIONS' },
        ],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    const history = wrapper.findAll('details').find(item => item.find('summary').text() === '查看这条回答的解释与出处')
    expect(history?.exists()).toBe(true)
    expect(history?.text()).toContain('先检查行动是否已经结算')
    expect(history?.text()).toContain('被明确写为即时效果')
    expect(history?.text()).toContain('引用属于当前规则书')
    expect(history?.text()).toContain('回合结束 · 第 4 页')
    expect(history?.text()).toContain('结算本轮。')
  })

  it('shows a cited concept comparison side by side with its practical boundary', () => {
    const comparisonAnswer = {
      ...answered,
      conceptComparisons: [{
        leftConcept: 'Influence',
        leftDefinition: '用于跳过卡牌。',
        rightConcept: 'Goodwill',
        rightDefinition: '用于终局计分。',
        commonGround: '两者使用同一实体标记。',
        keyDifference: 'Goodwill 不能作为 Influence 花费。',
        practicalBoundary: '起草时花 Influence；终局计算 Goodwill。',
        basis: 'RESOURCE_FUNCTION' as const,
      }, {
        leftConcept: '白天进入规则',
        leftDefinition: '只在白天适用。',
        rightConcept: '夜晚进入规则',
        rightDefinition: '只在夜晚适用。',
        commonGround: '两者都约束进入动作。',
        keyDifference: '适用时段不同，因此并不互相覆盖。',
        practicalBoundary: '白天使用前者，夜晚使用后者。',
        basis: 'RULE_SCOPE' as const,
      }],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: {
        ...baseProps,
        answer: answered,
        answeredQuestion: '什么时候结算？',
        answerTurns: [
          { question: 'Influence 和 Goodwill 有什么区别？', answer: comparisonAnswer, learningIntent: null },
          { question: '什么时候结算？', answer: answered, learningIntent: null },
        ],
      },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('这两个规则概念有什么区别')
    expect(wrapper.text()).toContain('Influence')
    expect(wrapper.text()).toContain('Goodwill')
    expect(wrapper.text()).toContain('两者使用同一实体标记')
    expect(wrapper.text()).toContain('两者都约束进入动作')
    expect(wrapper.text()).toContain('Goodwill 不能作为 Influence 花费')
    expect(wrapper.text()).toContain('起草时花 Influence；终局计算 Goodwill')
    expect(wrapper.text()).toContain('适用范围不同')
    expect(wrapper.text()).toContain('白天使用前者，夜晚使用后者')
  })

  it('shows every cited rule option with the shared selection rule and per-option result', () => {
    const optionAnswer = {
      ...answered,
      ruleOptions: [
        { decisionContext: '招募一张牌', selectionRule: '必须从三种来源中选择一种', optionName: 'Park', availabilityCondition: 'Park 中有牌', result: '拿取后立即补牌', basis: 'SOURCE_SELECTION' as const },
        { decisionContext: '招募一张牌', selectionRule: '必须从三种来源中选择一种', optionName: '对手 Yard', availabilityCondition: '任一对手 Yard 中有牌', result: '拿取后对手不补牌', basis: 'SOURCE_SELECTION' as const },
        { decisionContext: '招募一张牌', selectionRule: '必须从三种来源中选择一种', optionName: 'Park deck', availabilityCondition: '可以从牌库抽取', result: '抽取牌库顶牌', basis: 'SOURCE_SELECTION' as const },
      ],
    }
    const wrapper = mount(LessonAnswerPanel, {
      props: { ...baseProps, answer: optionAnswer, answeredQuestion: '可以从哪里招募？', answerTurns: [] },
      global: { stubs: { VoiceQuestionCapture: true } },
    })

    expect(wrapper.text()).toContain('完整规则选项')
    expect(wrapper.text()).toContain('必须从三种来源中选择一种')
    expect(wrapper.text()).toContain('拿取后立即补牌')
    expect(wrapper.text()).toContain('拿取后对手不补牌')
    expect(wrapper.text()).toContain('抽取牌库顶牌')
  })
})
