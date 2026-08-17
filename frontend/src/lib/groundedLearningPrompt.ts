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

/** Builds a self-contained follow-up without silently dropping part of the player's question. */
export function groundedLearningPrompt(
  t: TranslateLearningPrompt,
  intent: LearningIntent,
  anchorQuestion: string,
) {
  const key = learningPromptKey(intent)
  return t(key, { question: anchorQuestion.trim() })
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
