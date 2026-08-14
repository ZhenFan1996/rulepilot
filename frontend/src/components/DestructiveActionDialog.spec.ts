import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import DestructiveActionDialog from './DestructiveActionDialog.vue'

const Harness = defineComponent({
  components: { DestructiveActionDialog },
  setup() {
    const open = ref(false)
    const pending = ref(false)
    const error = ref('')
    const completed = ref(false)
    const completionTarget = ref<HTMLElement | null>(null)
    const confirmations = ref(0)
    function confirm() {
      confirmations.value += 1
    }
    function restoreFocus() {
      return completed.value ? completionTarget.value : null
    }
    return { completed, completionTarget, confirm, confirmations, error, open, pending, restoreFocus }
  },
  template: `
    <button id="opener" @click="open = true">Delete item</button>
    <h1 ref="completionTarget" tabindex="-1">Items</h1>
    <DestructiveActionDialog
      :open="open"
      :pending="pending"
      :error="error"
      title="Delete this rulebook?"
      description="Its pages and guides cannot be recovered."
      cancel-label="Keep rulebook"
      confirm-label="Delete rulebook"
      pending-label="Deleting…"
      retry-label="Try deletion again"
      :restore-focus="restoreFocus"
      @cancel="open = false"
      @confirm="confirm"
    />
  `,
})

describe('DestructiveActionDialog', () => {
  afterEach(() => {
    document.documentElement.classList.remove('modal-scroll-locked')
    document.documentElement.style.overflow = ''
    document.body.style.overflow = ''
    document.body.style.paddingRight = ''
    document.body.innerHTML = ''
  })

  it('opens on the safe action and restores its actual opener when cancelled', async () => {
    const wrapper = mount(Harness, { attachTo: document.body })
    const opener = wrapper.get<HTMLButtonElement>('#opener')
    opener.element.focus()
    await opener.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')!
    const cancel = [...dialog.querySelectorAll('button')].find(button => button.textContent?.includes('Keep rulebook'))!
    expect(document.activeElement).toBe(cancel)
    expect(document.documentElement.classList.contains('modal-scroll-locked')).toBe(true)

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    await flushPromises()

    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull()
    expect(document.activeElement).toBe(opener.element)
    wrapper.unmount()
  })

  it('cannot close or submit again while the destructive request is pending', async () => {
    const wrapper = mount(Harness, { attachTo: document.body })
    await wrapper.get('#opener').trigger('click')
    await flushPromises()
    wrapper.vm.pending = true
    await flushPromises()

    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')!
    const buttons = [...dialog.querySelectorAll<HTMLButtonElement>('button')]
    expect(buttons.every(button => button.disabled)).toBe(true)
    expect(dialog.textContent).toContain('Deleting…')

    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }))
    dialog.parentElement!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    buttons.at(-1)!.click()
    await flushPromises()

    expect(document.body.querySelector('[role="alertdialog"]')).not.toBeNull()
    expect(wrapper.vm.confirmations).toBe(0)
    wrapper.unmount()
  })

  it('announces an in-context failure and exposes an explicit retry action', async () => {
    const wrapper = mount(Harness, { attachTo: document.body })
    await wrapper.get('#opener').trigger('click')
    wrapper.vm.error = 'The request did not reach the server.'
    await flushPromises()

    const dialog = document.body.querySelector<HTMLElement>('[role="alertdialog"]')!
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('did not reach the server')
    const retry = [...dialog.querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('Try deletion again'))!
    retry.click()
    expect(wrapper.vm.confirmations).toBe(1)
    wrapper.unmount()
  })

  it('uses an explicit stable destination when success removes the opener', async () => {
    const wrapper = mount(Harness, { attachTo: document.body })
    await wrapper.get('#opener').trigger('click')
    await flushPromises()

    wrapper.vm.completed = true
    wrapper.vm.open = false
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('h1').element)
    wrapper.unmount()
  })
})
