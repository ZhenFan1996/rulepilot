import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import RulebookDocumentList from './RulebookDocumentList.vue'
import type { DocumentResponse } from './types'

function document(id: string, status: string): DocumentResponse {
  return {
    document: {
      id, gameEditionId: null, title: `${id}.pdf`, officialSourceUrl: null, officialCoverUrl: null,
    },
    latestVersion: { id: `version-${id}`, originalFilename: `${id}.pdf`, size: 2048, status },
  }
}

describe('RulebookDocumentList player status', () => {
  it('does not collapse processing, rulebook readability, and recovery into one success flag', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'catalog', component: { template: '<div />' } },
        { path: '/rulebooks/:versionId', name: 'rulebook-reader', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(RulebookDocumentList, {
      props: {
        loading: false,
        documents: [document('reading', 'EXTRACTING'), document('ready', 'READY'), document('failed', 'FAILED')],
        suggestionStates: {}, deletingDocumentId: '', preparingVersionId: '',
      },
      global: { plugins: [router] },
    })

    const statuses = wrapper.findAll('[data-testid="player-work-status"]')
    expect(statuses.map(status => status.text())).toEqual(['读取规则书', '规则书可读', '需要处理'])
    expect(statuses.map(status => status.attributes('data-player-work-readiness')))
      .toEqual(['unavailable', 'usable', 'unavailable'])
    expect(wrapper.text()).not.toContain('可以阅读和答疑')
  })
})
