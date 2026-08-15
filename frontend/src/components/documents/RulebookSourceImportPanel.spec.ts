import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'

import type { RulebookCandidate, RulebookDiscoveryCopy } from './types'
import RulebookSourceImportPanel from './RulebookSourceImportPanel.vue'

const copy = {
  action: 'Discover', loading: 'Searching', title: 'Importable sources', detail: 'Importable detail',
  noImportableTitle: 'No importable rulebook yet', noImportableDetail: 'Continue from a listing or use local upload.',
  identityOnlyTitle: 'Game identity references', identityOnlyDetail: 'Not rulebook choices.',
  unavailable: 'Unavailable', empty: 'Empty', error: 'Error',
  sources: {
    PUBLISHER: 'Publisher', TRUSTED_REPOSITORY: 'Repository',
    COMMUNITY_PLATFORM: 'Community', PUBLIC_WEB: 'Public web',
  },
  capabilities: {
    DIRECT_DOCUMENT: 'Confirmed document',
    CONTIGUOUS_RULE_PAGES: 'Confirmed page sequence',
    DOCUMENT_LISTING: 'Document listing only',
    GAME_INFO_ONLY: 'Game information; no document',
    UNVERIFIED_PAGE: 'Document not verified',
  },
  direct: 'Direct', gallery: 'Pages', page: 'Page', use: 'Use source',
  continueListing: 'Continue at listing', reviewUnverified: 'Review page', localUpload: 'Use local upload',
  publisher: 'Publisher', language: 'Language', languageVerified: 'verified', languageReview: 'review', edition: 'Edition',
  searchSteps: ['one'],
} satisfies RulebookDiscoveryCopy

function candidate(capability: RulebookCandidate['capability'], index: number): RulebookCandidate {
  return {
    title: `Opaque source ${index}`,
    url: `https://source${index}.example/path`,
    publisher: 'Opaque Studio', language: 'en', edition: 'First', sourceDomain: `source${index}.example`,
    officialDomainVerified: true, languageVerified: true, sourceType: 'PUBLISHER',
    acquisitionMode: capability === 'DIRECT_DOCUMENT' ? 'DIRECT_PDF'
      : capability === 'CONTIGUOUS_RULE_PAGES' ? 'IMAGE_GALLERY' : 'SOURCE_PAGE',
    capability,
    capabilityEvidence: [capability === 'DIRECT_DOCUMENT' ? 'DOCUMENT_RESPONSE_CONFIRMED'
      : capability === 'CONTIGUOUS_RULE_PAGES' ? 'ORDERED_PAGE_SEQUENCE_CONFIRMED'
        : 'HTML_PAGE_WITHOUT_DOCUMENT_CAPABILITY'],
    capabilityCheckedAt: '2026-08-15T12:00:00Z',
    nextAction: capability === 'DIRECT_DOCUMENT' ? 'IMPORT_DOCUMENT'
      : capability === 'CONTIGUOUS_RULE_PAGES' ? 'IMPORT_PAGE_SEQUENCE'
        : capability === 'DOCUMENT_LISTING' ? 'CONTINUE_ON_SOURCE'
          : capability === 'GAME_INFO_ONLY' ? 'USE_FOR_IDENTITY_ONLY' : 'REVIEW_OR_UPLOAD',
  }
}

describe('RulebookSourceImportPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('rulepilot:locale', 'en')
  })

  it('uses capability rather than title or URL to expose import actions', async () => {
    const candidates = [
      candidate('DIRECT_DOCUMENT', 1),
      candidate('CONTIGUOUS_RULE_PAGES', 2),
      candidate('DOCUMENT_LISTING', 3),
      candidate('GAME_INFO_ONLY', 4),
      candidate('UNVERIFIED_PAGE', 5),
    ]
    const wrapper = mount(RulebookSourceImportPanel, {
      props: {
        selectedEdition: {
          game: { id: 'game', name: 'Opaque Atlas' },
          edition: { id: 'edition', name: 'First', language: 'en' },
          bggMetadata: null,
        },
        status: 'success', candidates, copy,
      },
    })

    expect(wrapper.findAll('button').filter(button => button.text() === 'Use source')).toHaveLength(2)
    expect(wrapper.get('[data-capability="DOCUMENT_LISTING"] button').text()).toBe('Continue at listing')
    expect(wrapper.get('[data-capability="UNVERIFIED_PAGE"] button').text()).toBe('Review page')
    expect(wrapper.get('[data-capability="GAME_INFO_ONLY"]').find('button').exists()).toBe(false)
    expect(wrapper.get('section[aria-label="Game identity references"]').text()).toContain('Opaque source 4')

    await wrapper.get('[data-capability="DIRECT_DOCUMENT"] button').trigger('click')
    await wrapper.get('[data-capability="DOCUMENT_LISTING"] button').trigger('click')
    expect(wrapper.emitted('choose')).toEqual([[candidates[0]], [candidates[2]]])
  })

  it('states that discovery found no importable document and keeps local upload actionable', () => {
    const wrapper = mount(RulebookSourceImportPanel, {
      props: {
        selectedEdition: {
          game: { id: 'game', name: 'Opaque Atlas' },
          edition: { id: 'edition', name: 'First', language: 'en' },
          bggMetadata: null,
        },
        status: 'success',
        candidates: [candidate('DOCUMENT_LISTING', 1), candidate('GAME_INFO_ONLY', 2)],
        copy,
      },
    })

    expect(wrapper.text()).toContain('No importable rulebook yet')
    expect(wrapper.get('a[href="#rulebook-file"]').text()).toBe('Use local upload')
    expect(wrapper.findAll('button').some(button => button.text() === 'Use source')).toBe(false)
  })
})
