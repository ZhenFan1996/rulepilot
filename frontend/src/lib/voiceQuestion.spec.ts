import { describe, expect, it } from 'vitest'

import { mergeVoiceQuestion, normalizeVoiceTranscript } from './voiceQuestion'

describe('voice question transcript', () => {
  it('normalizes interim speech spacing', () => {
    expect(normalizeVoiceTranscript('  When   can I score?  ')).toBe('When can I score?')
  })

  it('appends a transcript without discarding an existing question', () => {
    expect(mergeVoiceQuestion('这张牌怎么用？', '  是否消耗行动？ ')).toBe(
      '这张牌怎么用？\n是否消耗行动？',
    )
  })

  it('enforces the question boundary after merging', () => {
    const merged = mergeVoiceQuestion('a'.repeat(799), 'voice')

    expect(merged.length).toBeLessThanOrEqual(800)
    expect(merged).toBe('a'.repeat(799))
  })
})
