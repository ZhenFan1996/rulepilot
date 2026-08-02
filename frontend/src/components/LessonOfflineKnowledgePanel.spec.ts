import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonOfflineKnowledgePanel from './LessonOfflineKnowledgePanel.vue'
import { setLocale } from '@/lib/locale'

const entries = [{
  question: '平局时怎么判断？',
  cachedAt: '2026-07-24T12:30:00.000Z',
  answer: {
    status: 'ANSWERED' as const, shortVerdict: '金币更多的玩家获胜。', explanation: '比较金币数量。',
    citations: [{ chunkId: 'chunk-1', sectionType: 'end', heading: 'Tie break', excerpt: 'Most gold wins.', pageFrom: 4, pageTo: 4 }],
    exceptions: [], confidence: 'HIGH' as const, official: false, confirmedRulingId: null, confirmedRulingVersion: null, clarification: null,
  },
  ruling: {
    id: 'ruling-1', shortVerdict: '金币更多的玩家获胜。', explanation: '比较金币数量。',
    citations: [{ chunkId: 'ruling-chunk-1', sectionType: 'end', heading: 'Tie break', excerpt: 'Most gold wins.', pageFrom: 4, pageTo: 4 }],
    exceptions: [], confidence: 'HIGH' as const, status: 'CONFIRMED' as const, version: 1,
  },
}]

function mountPanel() {
  return mount(LessonOfflineKnowledgePanel, { props: { entries } })
}

describe('LessonOfflineKnowledgePanel', () => {
  afterEach(() => setLocale('zh-CN'))

  it('renders cached answers and citations without changing their stored wording', () => {
    const wrapper = mountPanel()
    expect(wrapper.text()).toContain('本局已缓存规则结论')
    expect(wrapper.text()).toContain('平局时怎么判断？')
    expect(wrapper.text()).toContain('金币更多的玩家获胜。')
    expect(wrapper.text()).toContain('第 4 页')
  })

  it('localizes offline metadata and page formatting without translating cached evidence', () => {
    setLocale('en')
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('Available offline')
    expect(wrapper.text()).toContain('Cached rule answers')
    expect(wrapper.text()).toContain('1 entry')
    expect(wrapper.text()).toContain('Confirmed ruling')
    expect(wrapper.text()).toContain('Page 4')
    expect(wrapper.text()).toContain('金币更多的玩家获胜。')
  })
})
