import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, onMounted } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import { notifySessionCleared } from '@/lib/authSession'
import App from './App.vue'

describe('App session boundary', () => {
  it('remounts the active route immediately after logout so private view state is discarded', async () => {
    const mounted = vi.fn()
    const PrivateRoute = defineComponent({
      setup() {
        onMounted(mounted)
        return () => 'owner-only lesson list'
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/lessons', component: PrivateRoute }],
    })
    await router.push('/lessons')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })
    await flushPromises()

    notifySessionCleared()
    await wrapper.vm.$nextTick()

    expect(mounted).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
