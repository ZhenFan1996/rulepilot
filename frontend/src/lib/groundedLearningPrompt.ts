import type { LearningIntent } from '@/composables/useLessonAnswers'

type LearningPromptKey =
  | 'lesson.answer.prompt.simplify'
  | 'lesson.answer.prompt.example'
  | 'lesson.answer.prompt.define'
  | 'lesson.answer.prompt.why'
  | 'lesson.answer.prompt.exceptions'
  | 'lesson.answer.prompt.source'
  | 'lesson.answer.prompt.verify'

type TranslateLearningPrompt = (key: LearningPromptKey, variables: { question: string }) => string

const MAXIMUM_QUESTION_LENGTH = 800

/** Builds a self-contained follow-up while preserving the policy suffix inside the API question budget. */
export function groundedLearningPrompt(
  t: TranslateLearningPrompt,
  intent: LearningIntent,
  anchorQuestion: string,
) {
  const key = learningPromptKey(intent)
  const emptyPrompt = t(key, { question: '' })
  const available = Math.max(0, MAXIMUM_QUESTION_LENGTH - emptyPrompt.length)
  const anchor = anchorQuestion.trim().slice(0, available)
  return t(key, { question: anchor })
}

function learningPromptKey(intent: LearningIntent): LearningPromptKey {
  switch (intent) {
    case 'SIMPLIFY': return 'lesson.answer.prompt.simplify'
    case 'EXAMPLE': return 'lesson.answer.prompt.example'
    case 'DEFINE': return 'lesson.answer.prompt.define'
    case 'WHY': return 'lesson.answer.prompt.why'
    case 'EXCEPTIONS': return 'lesson.answer.prompt.exceptions'
    case 'SOURCE': return 'lesson.answer.prompt.source'
    case 'VERIFY': return 'lesson.answer.prompt.verify'
  }
}
