import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import PublicLibraryView from './PublicLibraryView.vue'

describe('PublicLibraryView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows an anonymous public lesson with its game cover and reading route', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json([{
      teachingPlanId: 'plan-1', rulebookTitle: 'Wingspan Rules', officialSourceUrl: 'https://publisher.example/rules.pdf',
      gameCover: { gameName: 'Wingspan', imageUrl: 'https://cf.geekdo-images.com/wingspan.jpg', attributionUrl: 'https://boardgamegeek.com/boardgame/266192', attributionLabel: 'BoardGameGeek' },
      sectionCount: 8, stepCount: 51,
    }])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: PublicLibraryView },
        { path: '/read/:planId', name: 'public-lesson', component: { template: '<div />' } },
      ],
    })
    await router.push('/library')
    await router.isReady()

    const wrapper = mount(PublicLibraryView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Wingspan')
    expect(wrapper.text()).toContain('8 章 · 51 步')
    expect(wrapper.get('img[alt="Wingspan 的游戏封面"]').attributes('src')).toContain('cf.geekdo-images.com')
    expect(wrapper.get('a[href="/read/plan-1"]')).toBeTruthy()
  })
})
