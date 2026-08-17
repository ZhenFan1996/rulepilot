import { describe, expect, it } from 'vitest'

import {
  playerFacingExplanation,
  playerFacingWalkthroughSteps,
} from './playerFacingAnswer'

describe('player-facing answer presentation', () => {
  it('preserves the model explanation instead of using browser-side lexical heuristics to hide it', () => {
    expect(playerFacingExplanation({
      shortVerdict: '顺时针进行；每回合打出一张人格牌并执行，打出 Tribune 时收回已打出的人格牌。',
      explanation: '游戏按顺时针进行。轮到你时打出一张人格牌并执行；如果打出 Tribune，就把已打出的人格牌收回。',
    })).toBe('游戏按顺时针进行。轮到你时打出一张人格牌并执行；如果打出 Tribune，就把已打出的人格牌收回。')
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

  it('preserves English prose without language-specific stop-word filtering', () => {
    expect(playerFacingExplanation({
      shortVerdict: 'Play one personality card, then carry out its action.',
      explanation: 'On your turn, play one personality card, then carry out that card\'s action.',
    })).toBe("On your turn, play one personality card, then carry out that card's action.")

    expect(playerFacingExplanation({
      shortVerdict: 'Play one personality card and carry out its action.',
      explanation: 'If you play Tribune, first recover all previously played personality cards.',
    })).toContain('If you play Tribune')
  })

  it('preserves complete model-authored walkthroughs without trying to judge their value in the browser', () => {
    const duplicateSteps = [
      { instruction: '打出一张人格牌。', explanation: '轮到你时选择并打出一张人格牌。', orderBasis: 'RULE_ORDER' as const },
      { instruction: '执行该牌行动。', explanation: '打出后执行这张牌的行动。', orderBasis: 'RULE_ORDER' as const },
    ]

    expect(playerFacingWalkthroughSteps({
      shortVerdict: '轮到你时，打出一张人格牌，然后执行该牌行动。',
      explanation: '先选择并打出人格牌，再执行这张牌的行动。',
      walkthroughSteps: duplicateSteps,
    })).toEqual(duplicateSteps)

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
