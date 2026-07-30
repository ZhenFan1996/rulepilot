import { describe, expect, it } from 'vitest'

import { parseRulebookIconGlossary } from './rulebookIconGlossary'

const validGlossary = {
  status: 'READY',
  totalPages: 1,
  inspectedPages: 1,
  completePages: 1,
  warnings: [],
  icons: [{
    id: 'icon-1',
    name: '行动图标',
    visualDescription: '蓝色圆形中的白色手掌',
    explanation: '执行一次行动。',
    evidenceText: '行动：执行一次行动',
    meaningStatus: 'EXPLICIT',
    representativeOccurrenceId: 'occurrence-1',
    occurrences: [{
      id: 'occurrence-1',
      pageNumber: 1,
      x: 10,
      y: 20,
      width: 30,
      height: 40,
    }],
  }],
}

describe('parseRulebookIconGlossary', () => {
  it('accepts a complete evidence-grounded glossary', () => {
    expect(parseRulebookIconGlossary(validGlossary)).toEqual(validGlossary)
  })

  it('rejects explicit meanings without direct evidence text', () => {
    const invalid = structuredClone(validGlossary)
    invalid.icons[0]!.evidenceText = null as unknown as string

    expect(() => parseRulebookIconGlossary(invalid)).toThrow('invalid rulebook icon glossary response')
  })

  it('rejects an image id that is not one of the recorded occurrences', () => {
    const invalid = structuredClone(validGlossary)
    invalid.icons[0]!.representativeOccurrenceId = 'invented-occurrence'

    expect(() => parseRulebookIconGlossary(invalid)).toThrow('invalid rulebook icon glossary response')
  })
})
