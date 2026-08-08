import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'

import GameRecommendationChatView from './GameRecommendationChatView.vue'

describe('GameRecommendationChatView', () => {
  beforeEach(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))

  it('keeps the conversation focused and links to a separate complete catalog', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/discover', name: 'game-recommendations', component: GameRecommendationChatView },
        { path: '/discover/catalog', name: 'game-catalog-browse', component: { template: '<div />' } },
      ],
    })
    await router.push('/discover')
    await router.isReady()

    const wrapper = mount(GameRecommendationChatView, {
      global: {
        plugins: [router],
        stubs: {
          AppShell: { template: '<div><slot /></div>' },
          GameRecommendationAgent: { template: '<section data-testid="recommendation-conversation" />' },
          TabletopGlyph: true,
        },
      },
    })

    expect(wrapper.get('h1').text()).toBe('先聊聊今晚想玩什么')
    expect(wrapper.get('[data-testid="recommendation-conversation"]').attributes('data-testid')).toBe('recommendation-conversation')
    expect(wrapper.get('a[href="/discover/catalog"]').text()).toContain('打开完整桌游目录')
    expect(wrapper.find('#game-catalog').exists()).toBe(false)
  })
})
