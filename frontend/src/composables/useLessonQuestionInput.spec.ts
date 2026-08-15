import { ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useLessonQuestionInput } from '@/composables/useLessonQuestionInput'
import { setLocale } from '@/lib/locale'

function createInput(anchor = 'When does the milestone score?') {
  const question = ref('')
  const submitQuestion = vi.fn(async () => undefined)
  const clearAnswerFeedback = vi.fn()
  const closeCardOcr = vi.fn()
  const input = useLessonQuestionInput({
    question,
    learningAnchorQuestion: () => anchor,
    submitQuestion,
    clearAnswerFeedback,
    closeCardOcr,
  })
  return { question, submitQuestion, clearAnswerFeedback, closeCardOcr, input }
}

describe('useLessonQuestionInput', () => {
  afterEach(() => {
    setLocale('zh-CN')
  })

  it('submits a localized learning prompt with its typed intent', async () => {
    setLocale('en')
    const fixture = createInput()

    await fixture.input.requestLearningHelp('EXAMPLE')

    expect(fixture.question.value).toContain('For the question “When does the milestone score?”')
    expect(fixture.question.value).toContain('one concrete, legal table example')
    expect(fixture.submitQuestion).toHaveBeenCalledWith(fixture.question.value, 'EXAMPLE')
  })

  it('turns an answer challenge into an editable natural follow-up before sending', async () => {
    const fixture = createInput()

    await fixture.input.requestLearningHelp('VERIFY')

    expect(fixture.question.value).toContain('When does the milestone score?')
    expect(fixture.question.value).toContain('我还是有点不放心')
    expect(fixture.question.value).toContain('条件、时机或例外')
    expect(fixture.question.value).not.toMatch(/检索|证据不足|拒答/)
    expect(fixture.submitQuestion).not.toHaveBeenCalled()
  })

  it('lets the player explain what was unclear instead of auto-sending a meta prompt', async () => {
    const fixture = createInput('When does the milestone score?')

    await fixture.input.requestLearningHelp('SIMPLIFY')

    expect(fixture.question.value).toContain('我还是没听明白')
    expect(fixture.question.value).not.toContain('请用更简单的话回答')
    expect(fixture.submitQuestion).not.toHaveBeenCalled()
  })

  it('requests a rulebook-grounded definition instead of a model-knowledge definition', async () => {
    setLocale('en')
    const fixture = createInput()

    await fixture.input.requestLearningHelp('DEFINE')

    expect(fixture.question.value).toContain('When does the milestone score?')
    expect(fixture.question.value).toContain('use only the current rulebook')
    expect(fixture.question.value).toContain('If the rulebook does not provide enough support, do not guess')
    expect(fixture.submitQuestion).toHaveBeenCalledWith(fixture.question.value, 'DEFINE')
  })

  it('requests the most direct source clause with a player-language explanation', async () => {
    setLocale('en')
    const fixture = createInput()

    await fixture.input.requestLearningHelp('SOURCE')

    expect(fixture.question.value).toContain('When does the milestone score?')
    expect(fixture.question.value).toContain('most direct rulebook support')
    expect(fixture.question.value).toContain('one or two direct sources')
    expect(fixture.submitQuestion).toHaveBeenCalledWith(fixture.question.value, 'SOURCE')
  })

  it('keeps a self-contained learning follow-up inside the question budget', async () => {
    setLocale('en')
    const fixture = createInput(`Which milestone applies after scoring? ${'x'.repeat(1_000)}`)

    await fixture.input.requestLearningHelp('VERIFY')

    expect(fixture.question.value.length).toBeLessThanOrEqual(800)
    expect(fixture.question.value).toContain('Which milestone applies after scoring?')
    expect(fixture.question.value).toContain('Could you check it once more')
    expect(fixture.submitQuestion).not.toHaveBeenCalled()
  })

  it('trims a direct question before submitting it without a learning intent', async () => {
    const fixture = createInput()
    fixture.question.value = '  How is this scored?  '

    await fixture.input.askQuestion()

    expect(fixture.submitQuestion).toHaveBeenCalledWith('How is this scored?', null)
  })

  it('turns card text and voice text into one editable question while clearing stale feedback', () => {
    setLocale('en')
    const fixture = createInput()

    fixture.input.useCardText('  Gain\n2 coins  ')
    fixture.input.useVoiceTranscript('and then score it')

    expect(fixture.question.value).toBe('Explain how this card is resolved using the current rulebook version:\nGain\n2 coins\nand then score it')
    expect(fixture.closeCardOcr).toHaveBeenCalledOnce()
    expect(fixture.clearAnswerFeedback).toHaveBeenCalledTimes(2)
  })
})
