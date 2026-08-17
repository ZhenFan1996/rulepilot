interface AnswerCopy {
  shortVerdict: string
  explanation: string
}

interface WalkthroughStep {
  instruction: string
  explanation: string
  orderBasis: 'RULE_ORDER' | 'EXPLANATION_ORDER'
}

interface AnswerWithWalkthrough extends AnswerCopy {
  walkthroughSteps?: WalkthroughStep[]
}

export function playerFacingExplanation(answer: AnswerCopy) {
  return answer.explanation.trim()
}

export function playerFacingWalkthroughSteps(answer: AnswerWithWalkthrough) {
  return answer.walkthroughSteps ?? []
}
