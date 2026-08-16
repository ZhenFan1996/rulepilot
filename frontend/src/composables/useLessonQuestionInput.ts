import type { Ref } from 'vue'

import type { LearningIntent } from '@/composables/useLessonAnswers'
import { buildCardQuestion } from '@/lib/cardOcr'
import { groundedLearningPrompt } from '@/lib/groundedLearningPrompt'
import { useLocale } from '@/lib/locale'
import { mergeVoiceQuestion } from '@/lib/voiceQuestion'

interface UseLessonQuestionInputOptions {
  question: Ref<string>
  learningAnchorQuestion: () => string
  submitQuestion: (text: string, learningIntent: LearningIntent | null) => Promise<void>
  clearAnswerFeedback: () => void
  closeCardOcr: () => void
}

export function useLessonQuestionInput(options: UseLessonQuestionInputOptions) {
  const { t } = useLocale()

  async function askQuestion() {
    await options.submitQuestion(options.question.value.trim(), null)
  }

  async function requestLearningHelp(intent: LearningIntent) {
    const prompt = groundedLearningPrompt(t, intent, options.learningAnchorQuestion())
    options.question.value = prompt
    if (intent === 'SIMPLIFY' || intent === 'VERIFY') return
    await options.submitQuestion(prompt, intent)
  }

  function useCardText(text: string) {
    options.question.value = buildCardQuestion(text, t('cardOcr.questionPrefix'))
    options.closeCardOcr()
    options.clearAnswerFeedback()
  }

  function useVoiceTranscript(text: string) {
    options.question.value = mergeVoiceQuestion(options.question.value, text)
    options.clearAnswerFeedback()
  }

  return {
    askQuestion,
    requestLearningHelp,
    useCardText,
    useVoiceTranscript,
  }
}
