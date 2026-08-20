import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RulebookLibraryView from './RulebookLibraryView.vue'

describe('RulebookLibraryView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps a prominent readable shelf entry outside dialogs', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json([{
      document: { id: 'document-1', title: 'Tea Garden Rules', sourceType: 'OFFICIAL_PDF', officialCoverUrl: 'https://images.example/tea.jpg', createdAt: '2026-08-21T00:00:00Z' },
      latestVersion: { id: 'version-1', versionNumber: 1, originalFilename: 'tea-garden.pdf', size: 2048000, status: 'READY', createdAt: '2026-08-21T00:00:00Z' },
    }])))
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: RulebookLibraryView }, { path: '/teach', name: 'teach', component: { template: '<div />' } }, { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: { template: '<div />' } },
    ] })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(RulebookLibraryView, { global: { plugins: [router], stubs: { AppShell: { template: '<main><slot /></main>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain('我的规则书')
    expect(wrapper.text()).toContain('Tea Garden Rules')
    expect(wrapper.get('a[href="/rulebooks/version-1"]').text()).toContain('打开阅读')
    expect(wrapper.findAll('main')).toHaveLength(1)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})
