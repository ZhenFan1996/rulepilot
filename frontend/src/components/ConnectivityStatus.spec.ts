import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import ConnectivityStatus from './ConnectivityStatus.vue'

describe('ConnectivityStatus', () => {
  afterEach(() => {
    vi.useRealTimers()
    setNavigatorOnline(true)
    setLocale('zh-CN')
    document.documentElement.classList.remove('connectivity-status-visible')
  })

  it('stays quiet while online, then announces offline and a bounded honest reconnection', async () => {
    vi.useFakeTimers()
    setNavigatorOnline(true)
    const wrapper = mount(ConnectivityStatus, { props: { reconnectedDurationMs: 4000 } })

    expect(wrapper.find('[data-testid="connectivity-status"]').exists()).toBe(false)
    expect(document.documentElement.classList.contains('connectivity-status-visible')).toBe(false)

    window.dispatchEvent(new Event('online'))
    await flushPromises()
    expect(wrapper.find('[data-testid="connectivity-status"]').exists()).toBe(false)

    window.dispatchEvent(new Event('offline'))
    await flushPromises()

    expect(wrapper.text()).toContain('当前离线')
    expect(wrapper.text()).toContain('登录、搜索、上传和生成需联网')
    expect(wrapper.get('[role="status"]').attributes('aria-live')).toBe('polite')
    expect(document.documentElement.classList.contains('connectivity-status-visible')).toBe(true)

    window.dispatchEvent(new Event('online'))
    await flushPromises()

    expect(wrapper.text()).toContain('设备已重新联网')
    expect(wrapper.text()).toContain('不会自动重试')
    expect(document.documentElement.classList.contains('connectivity-status-visible')).toBe(true)

    await vi.advanceTimersByTimeAsync(4000)
    expect(wrapper.find('[data-testid="connectivity-status"]').exists()).toBe(false)
    expect(document.documentElement.classList.contains('connectivity-status-visible')).toBe(false)
    wrapper.unmount()
  })

  it('is immediately visible offline, reacts to locale, and cleans up global layout state', async () => {
    setNavigatorOnline(false)
    const wrapper = mount(ConnectivityStatus)

    expect(wrapper.text()).toContain('当前离线')
    setLocale('en')
    await flushPromises()
    expect(wrapper.text()).toContain("You're offline")
    expect(wrapper.text()).toContain('Cached content may still be available')

    wrapper.unmount()
    expect(document.documentElement.classList.contains('connectivity-status-visible')).toBe(false)
  })
})

function setNavigatorOnline(online: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: online })
}
