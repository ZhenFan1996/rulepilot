import { describe, expect, it } from 'vitest'

import {
  playerFacingExplanation,
  playerFacingWalkthroughSteps,
} from './playerFacingAnswer'

describe('player-facing answer presentation', () => {
  it('omits a Chinese explanation that only repeats the verdict in audit-style prose', () => {
    expect(playerFacingExplanation({
      shortVerdict: '顺时针进行；每回合打出一张人格牌并执行，打出 Tribune 时收回已打出的人格牌。',
      explanation: '游戏按顺时针进行。轮到你时打出一张人格牌并执行；如果打出 Tribune，就把已打出的人格牌收回。',
    })).toBe('')
  })

  it('keeps a qualification, exception, quantity, or timing boundary that the verdict does not contain', () => {
    expect(playerFacingExplanation({
      shortVerdict: '你可以收回已打出的人格牌。',
      explanation: '只有打出 Tribune 时才能收回，而且还可以选择增加一名殖民者。',
    })).toBe('只有打出 Tribune 时才能收回，而且还可以选择增加一名殖民者。')

    expect(playerFacingExplanation({
      shortVerdict: '每张 Jupiter 人格牌都按非砖城房屋计分。',
      explanation: '每张牌分别计分；2 张 Jupiter 牌与 9 栋房屋应计算为 2 × 9 = 18 分。',
    })).toContain('18 分')
  })

  it('applies the same progressive disclosure to English without dropping a new condition', () => {
    expect(playerFacingExplanation({
      shortVerdict: 'Play one personality card, then carry out its action.',
      explanation: 'On your turn, play one personality card, then carry out that card\'s action.',
    })).toBe('')

    expect(playerFacingExplanation({
      shortVerdict: 'Play one personality card and carry out its action.',
      explanation: 'If you play Tribune, first recover all previously played personality cards.',
    })).toContain('If you play Tribune')
  })

  it('suppresses a walkthrough only when the whole sequence adds no usable information', () => {
    const duplicateSteps = [
      { instruction: '打出一张人格牌。', explanation: '轮到你时选择并打出一张人格牌。', orderBasis: 'RULE_ORDER' as const },
      { instruction: '执行该牌行动。', explanation: '打出后执行这张牌的行动。', orderBasis: 'RULE_ORDER' as const },
    ]

    expect(playerFacingWalkthroughSteps({
      shortVerdict: '轮到你时，打出一张人格牌，然后执行该牌行动。',
      explanation: '先选择并打出人格牌，再执行这张牌的行动。',
      walkthroughSteps: duplicateSteps,
    })).toEqual([])

    expect(playerFacingWalkthroughSteps({
      shortVerdict: '执行 Architect 行动。',
      explanation: '移动后可以建造房屋。',
      walkthroughSteps: [
        { instruction: '先移动殖民者。', explanation: '移动总步数不能超过殖民者数量。', orderBasis: 'RULE_ORDER' as const },
        { instruction: '再建造房屋。', explanation: '每座房屋都要支付对应费用。', orderBasis: 'RULE_ORDER' as const },
      ],
    })).toHaveLength(2)
  })
})
