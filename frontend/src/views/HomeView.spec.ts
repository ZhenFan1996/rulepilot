import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import HomeView from './HomeView.vue'

describe('HomeView', () => {
  it('prioritizes rulebook import and guided teaching before questions', () => {
    const wrapper = mount(HomeView, {
      global: {
        stubs: {
          RouterLink: {
            template: '<a><slot /></a>',
          },
        },
      },
    })

    expect(wrapper.text()).toContain('导入规则书')
    expect(wrapper.text()).toContain('查看讲解流程')
    expect(wrapper.text()).toContain('讲解后继续答疑')
    expect(wrapper.text()).toContain('结束条件与计分')
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
