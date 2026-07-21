import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import PublicLessonView from './PublicLessonView.vue'

describe('PublicLessonView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders a no-login lesson with cited visual crops and an official source link', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({
      teachingPlanId: 'plan-1',
      documentVersionId: 'version-1',
      rulebookTitle: 'Wingspan Rules',
      officialSourceUrl: 'https://publisher.example/rules.pdf',
      lesson: {
        id: 'lesson-1', status: 'DRAFT_READY', sections: [{
          position: 1, title: '摆好鸟类保护区', visualCaption: '先把玩家板放在自己面前。', steps: [{
            position: 1, heading: '放置玩家板', kind: 'VISUAL', text: '把玩家板放在自己面前。', sourcePages: [2],
            visualFocus: { pageNumber: 2, label: '玩家板设置', x: 100, y: 200, width: 500, height: 300 },
          }],
        }],
      },
    })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/read/:planId', name: 'public-lesson', component: PublicLessonView },
      ],
    })
    await router.push('/read/plan-1')
    await router.isReady()

    const wrapper = mount(PublicLessonView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Wingspan Rules')
    expect(wrapper.text()).toContain('放置玩家板')
    expect(wrapper.get('a[href="/api/public/lessons/plan-1/rulebook"]').text()).toContain('官方原规则书')
    expect(wrapper.get('img[alt*="玩家板设置"]').attributes('src'))
      .toContain('/api/public/lessons/plan-1/pages/2/image/crop?x=100&y=200&width=500&height=300')
  })
})
