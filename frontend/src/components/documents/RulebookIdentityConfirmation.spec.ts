import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'

import { setLocale } from '@/lib/locale'
import RulebookIdentityConfirmation from './RulebookIdentityConfirmation.vue'

describe('RulebookIdentityConfirmation', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('rulepilot:locale', 'en')
    setLocale('en')
  })

  it('keeps catalog, discovery, and source identities separate when the selected edition changed', async () => {
    const wrapper = mount(RulebookIdentityConfirmation, {
      props: {
        modelValue: false,
        target: {
          editionId: 'edition-b', gameName: 'Opaque Atlas', editionName: 'Second', language: 'en',
        },
        sourceContext: {
          editionId: 'edition-a', gameName: 'Opaque Atlas', editionName: 'First', language: 'und',
        },
        source: { edition: 'First', language: '', languageVerified: false },
        disabled: false,
        'onUpdate:modelValue': (value: boolean) => wrapper.setProps({ modelValue: value }),
      },
    })

    expect(wrapper.get('[data-testid="identity-target"]').text()).toContain('Second')
    expect(wrapper.get('[data-testid="identity-discovery"]').text()).toContain('First')
    expect(wrapper.get('[data-testid="identity-source"]').text()).toContain('Not stated')
    expect(wrapper.get('[role="alert"]').text()).toContain('selected edition changed')
    expect(wrapper.get('[role="alert"]').text()).toContain('source language is not verified')

    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(wrapper.emitted('update:modelValue')).toEqual([[true]])
  })

  it('does not present an unknown catalog language as a confirmed source language', () => {
    const wrapper = mount(RulebookIdentityConfirmation, {
      props: {
        modelValue: false,
        target: {
          editionId: 'edition-a', gameName: 'Opaque Atlas', editionName: 'First', language: 'und',
        },
        sourceContext: {
          editionId: 'edition-a', gameName: 'Opaque Atlas', editionName: 'First', language: 'und',
        },
        source: { edition: 'First', language: 'zh-CN', languageVerified: true },
        disabled: false,
      },
    })

    expect(wrapper.get('[data-testid="identity-target"]').text()).toContain('Not known')
    expect(wrapper.get('[data-testid="identity-source"]').text()).toContain('Simplified Chinese')
    expect(wrapper.get('[role="alert"]').text()).toContain('catalog language is not known')
  })
})
