import type { Ref } from 'vue'

import type { LearningIntent } from '@/composables/useLessonAnswers'
import { buildCardQuestion } from '@/lib/cardOcr'
import { useLocale } from '@/lib/locale'
import { mergeVoiceQuestion } from '@/lib/voiceQuestion'

interface UseLessonQuestionInputOptions {
  question: Ref<string>
  currentSectionTitle: () => string | null
  submitQuestion: (text: string, learningIntent: LearningIntent | null) => Promise<void>
  clearAnswerFeedback: () => void
  closeCardOcr: () => void
}

export function useLessonQuestionInput(options: UseLessonQuestionInputOptions) {
  const { t } = useLocale()

  function learningPrompt(intent: LearningIntent) {
    const title = options.currentSectionTitle() ?? t('lesson.answer.sectionFallback')
    switch (intent) {
      case 'SIMPLIFY':
        return t('lesson.answer.prompt.simplify', { title })
      case 'EXAMPLE':
        return t('lesson.answer.prompt.example', { title })
      case 'WHY':
        return t('lesson.answer.prompt.why', { title })
      case 'EXCEPTIONS':
        return t('lesson.answer.prompt.exceptions', { title })
    }
  }

  async function askCurrentSection() {
    await options.submitQuestion(options.question.value.trim(), null)
  }

  async function requestLearningHelp(intent: LearningIntent) {
    const prompt = learningPrompt(intent)
    options.question.value = prompt
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
    askCurrentSection,
    requestLearningHelp,
    useCardText,
    useVoiceTranscript,
  }
}
