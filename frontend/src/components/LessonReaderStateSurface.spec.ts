import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonReaderStateSurface from './LessonReaderStateSurface.vue'
import { setLocale } from '@/lib/locale'

function mountSurface(overrides: Record<string, unknown> = {}) {
  return mount(LessonReaderStateSurface, {
    props: { errorMessage: '', online: true, ...overrides },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('LessonReaderStateSurface', () => {
  afterEach(() => setLocale('zh-CN'))

  it('shows the empty route without owning navigation state', () => {
    const wrapper = mountSurface()
    expect(wrapper.text()).toContain('还没有可以继续的讲解')
    expect(wrapper.text()).toContain('开始导入')
  })

  it('keeps retry as an emitted intent and gives offline precedence over transport text', async () => {
    const wrapper = mountSurface({ errorMessage: 'temporary transport failure', online: true })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([[]])

    await wrapper.setProps({ online: false })
    expect(wrapper.text()).toContain('离线时无法加载')
    expect(wrapper.text()).not.toContain('temporary transport failure')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('localizes the empty and unavailable states', () => {
    setLocale('en')
    const empty = mountSurface()
    expect(empty.text()).toContain('There is no guide to continue yet')
    expect(empty.text()).toContain('Import a rulebook')

    const unavailable = mountSurface({ errorMessage: 'This guide could not be read right now.' })
    expect(unavailable.text()).toContain('This guide is unavailable right now')
    expect(unavailable.text()).toContain('Try again')
  })
})
