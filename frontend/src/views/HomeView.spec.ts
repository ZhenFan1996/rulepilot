import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import HomeView from './HomeView.vue'

describe('HomeView', () => {
  it('presents the three core table-side actions', () => {
    const wrapper = mount(HomeView, {
      global: {
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('开始讲解')
    expect(wrapper.text()).toContain('快速查规则')
    expect(wrapper.text()).toContain('了解实时桌局')
  })

  it('updates the accessible theme toggle label', async () => {
    document.documentElement.classList.remove('dark')
    const wrapper = mount(HomeView, {
      global: {
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    const toggle = wrapper.get('button[aria-label="切换到深色模式"]')
    await toggle.trigger('click')

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(wrapper.find('button[aria-label="切换到浅色模式"]').exists()).toBe(true)
    document.documentElement.classList.remove('dark')
  })
})
