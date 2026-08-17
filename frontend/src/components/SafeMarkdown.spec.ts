import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import SafeMarkdown from './SafeMarkdown.vue'

describe('SafeMarkdown', () => {
  it('renders useful markdown while keeping raw HTML and dangerous links inert', () => {
    const wrapper = mount(SafeMarkdown, {
      props: {
        source: [
          '**Best fit**',
          '',
          '- Keeps everyone involved',
          '- [Read the source](https://example.test/rules)',
          '',
          '<img src=x onerror="alert(1)">',
          '![tracking pixel](https://example.test/pixel.gif)',
          '[unsafe](javascript:alert(1))',
        ].join('\n'),
      },
    })

    expect(wrapper.get('strong').text()).toBe('Best fit')
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.get('a').attributes()).toMatchObject({
      href: 'https://example.test/rules',
      target: '_blank',
      rel: 'noopener noreferrer nofollow',
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror="alert(1)">')
    expect(wrapper.text()).toContain('tracking pixel')
    expect(wrapper.text()).toContain('[unsafe](javascript:alert(1))')
  })
})
