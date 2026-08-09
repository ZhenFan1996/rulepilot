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
      publicGame: { bggId: 266192, name: '翼展', bggUrl: 'https://boardgamegeek.com/boardgame/266192' },
      sectionCount: 8, stepCount: 51,
    }])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: PublicLibraryView },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
      ],
    })
    await router.push('/library')
    await router.isReady()

    const wrapper = mount(PublicLibraryView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('翼展')
    expect(wrapper.text()).toContain('8 章 · 51 步')
    expect(wrapper.get('img[alt="翼展 的游戏封面"]').attributes('src')).toBe('/api/public/lessons/plan-1/cover')
    expect(wrapper.get('img[alt="翼展 的游戏封面"]').classes()).toContain('object-contain')
    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).toContain('我的讲解')
    expect(wrapper.get('a[href="/read/plan-1"]')).toBeTruthy()
    expect(wrapper.get('a[href="/discover/266192"]').text()).toContain('桌游资料')
    expect(wrapper.get('img[alt="Powered by BoardGameGeek"]').attributes('src')).toBe('/powered-by-bgg-rgb.svg')
  })

  it('uses player-readable names, keeps the better duplicate, and lets a player search', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json([
      {
        teachingPlanId: 'root-old', rulebookTitle: 'Root Learning to Play Corpus Replay', officialSourceUrl: '',
        gameCover: null, publicGame: null, sectionCount: 10, stepCount: 55,
      },
      {
        teachingPlanId: 'root-best', rulebookTitle: 'Root: Learning to Play Rules', officialSourceUrl: '',
        gameCover: { gameName: 'Root: Learning to Play Rules', imageUrl: 'https://images.example/root.jpg', attributionUrl: 'https://boardgamegeek.com/root', attributionLabel: 'BoardGameGeek' },
        publicGame: null, sectionCount: 14, stepCount: 63,
      },
      {
        teachingPlanId: 'fort', rulebookTitle: 'Fort Rules', officialSourceUrl: '',
        gameCover: null, publicGame: null, sectionCount: 12, stepCount: 60,
      },
    ])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/library', name: 'public-library', component: PublicLibraryView },
        { path: '/teach', name: 'teach', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/catalog', name: 'catalog', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: { template: '<div />' } },
        { path: '/discover/:bggId', name: 'game-discovery', component: { template: '<div />' } },
      ],
    })
    await router.push('/library')
    await router.isReady()
    const wrapper = mount(PublicLibraryView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('2 款游戏可直接阅读')
    expect(wrapper.text()).not.toContain('Corpus Replay')
    expect(wrapper.findAll('a[href="/read/root-best"]')).toHaveLength(1)
    expect(wrapper.find('a[href="/read/root-old"]').exists()).toBe(false)
    expect(wrapper.find('.lesson-cover').exists()).toBe(true)

    await wrapper.get('input[placeholder="搜索游戏"]').setValue('fort')
    expect(wrapper.find('a[href="/read/fort"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/read/root-best"]').exists()).toBe(false)
  })
})
