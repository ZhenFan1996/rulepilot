import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonQuestionInput } from '@/composables/useLessonQuestionInput'
import { setLocale } from '@/lib/locale'

function createInput() {
  const question = ref('')
  const sectionTitle = ref<string | null>('Final scoring')
  const submitQuestion = vi.fn(async () => undefined)
  const clearAnswerFeedback = vi.fn()
  const closeCardOcr = vi.fn()
  const input = useLessonQuestionInput({
    question,
    currentSectionTitle: () => sectionTitle.value,
    submitQuestion,
    clearAnswerFeedback,
    closeCardOcr,
  })
  return { question, sectionTitle, submitQuestion, clearAnswerFeedback, closeCardOcr, input }
}

describe('useLessonQuestionInput', () => {
  afterEach(() => {
    setLocale('zh-CN')
  })

  it('submits a localized learning prompt with its typed intent', async () => {
    setLocale('en')
    const fixture = createInput()

    await fixture.input.requestLearningHelp('EXAMPLE')

    expect(fixture.question.value).toBe('Using the rules for “Final scoring”, walk through one concrete, legal table example.')
    expect(fixture.submitQuestion).toHaveBeenCalledWith(fixture.question.value, 'EXAMPLE')
  })

  it('trims a direct question before submitting it without a learning intent', async () => {
    const fixture = createInput()
    fixture.question.value = '  How is this scored?  '

    await fixture.input.askCurrentSection()

    expect(fixture.submitQuestion).toHaveBeenCalledWith('How is this scored?', null)
  })

  it('turns card text and voice text into one editable question while clearing stale feedback', () => {
    setLocale('en')
    const fixture = createInput()

    fixture.input.useCardText('  Gain\n2 coins  ')
    fixture.input.useVoiceTranscript('and then score it')

    expect(fixture.question.value).toBe('Explain how this card is resolved in this chapter using the current rulebook version:\nGain\n2 coins\nand then score it')
    expect(fixture.closeCardOcr).toHaveBeenCalledOnce()
    expect(fixture.clearAnswerFeedback).toHaveBeenCalledTimes(2)
  })
})
