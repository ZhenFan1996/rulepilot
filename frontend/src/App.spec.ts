import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, onMounted } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { notifySessionCleared } from '@/lib/authSession'
import { setLocale } from '@/lib/locale'
import App from './App.vue'

describe('App session boundary', () => {
  afterEach(() => {
    setLocale('zh-CN')
    document.title = 'RulePilot'
  })

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

  it('announces each route with a localized title and moves focus to new content', async () => {
    const HomeRoute = {
      template: '<main id="main-content" tabindex="-1"><h1>Home</h1></main>',
    }
    const GuidesRoute = {
      template: '<main id="main-content" tabindex="-1"><h1>Guides</h1></main>',
    }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: HomeRoute, meta: { titleKey: 'route.title.home' } },
        { path: '/lessons', component: GuidesRoute, meta: { titleKey: 'route.title.guides' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { plugins: [router] },
    })
    await flushPromises()

    expect(document.title).toBe('首页 · RulePilot')
    expect(wrapper.get('.skip-to-content').attributes('href')).toBe('#main-content')

    await router.push('/lessons')
    await flushPromises()

    expect(document.title).toBe('我的讲解 · RulePilot')
    expect(document.activeElement?.id).toBe('main-content')

    setLocale('en')
    await vi.waitFor(() => expect(document.title).toBe('My guides · RulePilot'))
    wrapper.unmount()
  })

  it('does not steal focus when a guarded navigation is cancelled', async () => {
    let allowNavigation = false
    const HomeRoute = {
      template: '<main id="main-content" tabindex="-1"><a id="catalog-link" href="/catalog">Catalog</a></main>',
    }
    const CatalogRoute = {
      template: '<main id="main-content" tabindex="-1"><h1>Catalog</h1></main>',
    }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: HomeRoute },
        { path: '/catalog', component: CatalogRoute },
      ],
    })
    router.beforeEach(to => to.path === '/catalog' && !allowNavigation ? false : true)
    await router.push('/')
    await router.isReady()
    const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router] } })
    await flushPromises()
    const opener = wrapper.get('#catalog-link').element as HTMLElement
    opener.focus()

    await router.push('/catalog')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/')
    expect(document.activeElement).toBe(opener)

    allowNavigation = true
    await router.push('/catalog')
    await flushPromises()
    expect(document.activeElement?.id).toBe('main-content')
    wrapper.unmount()
  })
})
