/* eslint-disable vue/one-component-per-file */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import { useModalFocus } from './useModalFocus'

const NestedModal = defineComponent({
  emits: ['close'],
  setup(_props, { emit }) {
    const dialog = ref<HTMLElement | null>(null)
    useModalFocus({ dialog, open: () => true, requestClose: () => emit('close') })
    return { dialog }
  },
  template: '<section ref="dialog" role="dialog" aria-modal="true" tabindex="-1"><button data-modal-initial-focus @click="$emit(\'close\')">Close nested</button><button>Nested last</button></section>',
})

const ModalHarness = defineComponent({
  components: { NestedModal },
  setup() {
    const open = ref(false)
    const nested = ref(false)
    const dialog = ref<HTMLElement | null>(null)
    useModalFocus({ dialog, open, requestClose: () => { open.value = false } })
    return { dialog, nested, open }
  },
  template: `
    <button id="opener" @click="open = true">Open</button>
    <section v-if="open" ref="dialog" role="dialog" aria-modal="true" tabindex="-1">
      <button data-modal-initial-focus @click="open = false">Close</button>
      <button id="nested-opener" @click="nested = true">Open nested</button>
      <button id="last">Last</button>
      <NestedModal v-if="nested" @close="nested = false" />
    </section>
  `,
})

describe('useModalFocus', () => {
  afterEach(() => {
    document.documentElement.classList.remove('modal-scroll-locked')
    document.documentElement.style.overflow = ''
    document.body.style.overflow = ''
    document.body.style.paddingRight = ''
    document.body.innerHTML = ''
  })

  it('moves focus inside, traps both Tab edges, closes with Escape, and restores the opener', async () => {
    const wrapper = mount(ModalHarness, { attachTo: document.body })
    const opener = wrapper.get<HTMLButtonElement>('#opener')
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()

    const close = wrapper.get<HTMLButtonElement>('[data-modal-initial-focus]')
    const last = wrapper.get<HTMLButtonElement>('#last')
    expect(document.activeElement).toBe(close.element)
    expect(document.documentElement.classList.contains('modal-scroll-locked')).toBe(true)
    expect(document.body.style.overflow).toBe('hidden')

    last.element.focus()
    last.element.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Tab' }))
    expect(document.activeElement).toBe(close.element)

    close.element.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Tab', shiftKey: true }))
    expect(document.activeElement).toBe(last.element)

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(opener.element)
    expect(document.body.style.overflow).toBe('')
    wrapper.unmount()
  })

  it('keeps the page locked and closes only the top layer before restoring its nested opener', async () => {
    const wrapper = mount(ModalHarness, { attachTo: document.body })
    await wrapper.get('#opener').trigger('click')
    await flushPromises()
    const nestedOpener = wrapper.get<HTMLButtonElement>('#nested-opener')
    nestedOpener.element.focus()
    await nestedOpener.trigger('click')
    await flushPromises()

    expect(document.activeElement?.textContent).toBe('Close nested')
    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()

    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    expect(document.activeElement).toBe(nestedOpener.element)
    expect(document.body.style.overflow).toBe('hidden')

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.body.style.overflow).toBe('')
    wrapper.unmount()
  })

  it('releases the stack and restores pre-existing document styles when an open modal unmounts', async () => {
    document.documentElement.style.overflow = 'clip'
    document.body.style.overflow = 'auto'
    document.body.style.paddingRight = '7px'
    const wrapper = mount(ModalHarness, { attachTo: document.body })
    await wrapper.get('#opener').trigger('click')
    await flushPromises()

    expect(document.body.style.overflow).toBe('hidden')
    wrapper.unmount()
    await flushPromises()

    expect(document.documentElement.style.overflow).toBe('clip')
    expect(document.body.style.overflow).toBe('auto')
    expect(document.body.style.paddingRight).toBe('7px')
    expect(document.documentElement.classList.contains('modal-scroll-locked')).toBe(false)
  })
})
