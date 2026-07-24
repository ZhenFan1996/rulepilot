import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

const tesseract = vi.hoisted(() => ({ createWorker: vi.fn() }))

vi.mock('tesseract.js', () => ({
  createWorker: tesseract.createWorker,
  OEM: { LSTM_ONLY: 1 },
  PSM: { SPARSE_TEXT: 11 },
}))

import CardOcrCapture from './CardOcrCapture.vue'
import { setLocale } from '@/lib/locale'

describe('CardOcrCapture', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    tesseract.createWorker.mockReset()
    setLocale('zh-CN')
  })

  it('presents private local card recognition in the player-selected language', () => {
    setLocale('en')
    const wrapper = mount(CardOcrCapture)

    expect(wrapper.text()).toContain('Read a card from a photo')
    expect(wrapper.text()).toContain('Recognition runs in this browser')
    expect(wrapper.text()).toContain('Photograph a card, or choose a photo already on this device')
    expect(wrapper.text()).toContain('Read the text')
    expect(wrapper.get('select').text()).toContain('Simplified Chinese + English')
  })

  it('keeps invalid-photo guidance local to the active player language', async () => {
    setLocale('en')
    const wrapper = mount(CardOcrCapture)
    const input = wrapper.get('input[type="file"]').element
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['not an image'], 'rules.pdf', { type: 'application/pdf' })] })

    await wrapper.get('input[type="file"]').trigger('change')

    expect(wrapper.text()).toContain('Choose a JPG, PNG, HEIC, or another image file.')
  })

  it('keeps a local OCR runtime failure actionable instead of exposing the browser error', async () => {
    setLocale('en')
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:card'), revokeObjectURL: vi.fn() })
    tesseract.createWorker.mockRejectedValue(new Error('network transport refused'))
    const wrapper = mount(CardOcrCapture)
    const input = wrapper.get('input[type="file"]').element
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['image'], 'card.png', { type: 'image/png' })] })

    await wrapper.get('input[type="file"]').trigger('change')
    await wrapper.findAll('button').find((button) => button.text() === 'Read the text')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Card recognition did not finish. Check your connection or try a clearer photo.')
    expect(wrapper.text()).not.toContain('network transport refused')
  })
})
