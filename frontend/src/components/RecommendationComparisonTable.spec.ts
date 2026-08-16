import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { setLocale } from '@/lib/locale'
import RecommendationComparisonTable from './RecommendationComparisonTable.vue'

const game = (bggId: number, name: string) => ({
  bggId,
  name,
  originalName: name,
  nameLocalized: false,
  publicationYear: null,
  overallRank: null,
  geekRating: 0,
  averageRating: 0,
  usersRated: 0,
  thumbnailUrl: '',
  minPlayers: 2,
  maxPlayers: 4,
  playingTimeMinutes: 60,
  averageWeight: 2.4,
  categories: [],
  mechanics: [],
  bggUrl: `https://example.test/${bggId}`,
})

describe('RecommendationComparisonTable', () => {
  afterEach(() => setLocale('zh-CN'))

  it('keeps candidate columns separate and renders missing experience as unknown', () => {
    setLocale('zh-CN')
    const wrapper = mount(RecommendationComparisonTable, {
      props: {
        responseLocale: 'en',
        comparison: {
          candidates: [
            { game: game(11, 'Candidate One'), fitClaims: [] },
            { game: game(22, 'Candidate Two'), fitClaims: [] },
          ],
          axes: [
            {
              subject: 'durationMinutes', label: 'Listed duration', capability: 'structured_metadata',
              cells: [
                { bggId: 11, status: 'observed', observationKind: 'structured_metadata', value: '45–60 min' },
                { bggId: 22, status: 'observed', observationKind: 'structured_metadata', value: '60–75 min' },
              ],
            },
            {
              subject: 'reportedExperience', label: 'Sourced player experience', capability: 'attributed_report',
              cells: [
                { bggId: 11, status: 'unknown', observationKind: '', value: '' },
                { bggId: 22, status: 'unknown', observationKind: '', value: '' },
              ],
            },
          ],
        },
      },
    })

    const table = wrapper.get('[data-testid="candidate-comparison"]')
    expect(table.text()).toContain('Side-by-side check')
    expect(table.text()).toContain('Candidate One')
    expect(table.text()).toContain('Candidate Two')
    expect(table.text()).toContain('45–60 min')
    expect(table.text()).toContain('60–75 min')
    expect(table.text().match(/Unknown from the available evidence/g)).toHaveLength(2)
    expect(table.text()).not.toContain('并排核对')
  })
})
