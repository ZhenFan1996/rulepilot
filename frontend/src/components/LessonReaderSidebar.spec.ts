import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import LessonReaderSidebar from './LessonReaderSidebar.vue'

const mediaModeAvailable = vi.fn((mode: 'TEXT' | 'AUDIO' | 'VIDEO') => mode !== 'AUDIO')

function mountSidebar(overrides = {}) {
  return mount(LessonReaderSidebar, {
    props: {
      lessonStatus: 'DRAFT_READY',
      sections: [
        { position: 1, topicKey: 'setup', title: '摆好桌面' },
        { position: 2, topicKey: 'turn', title: '走完一回合' },
      ],
      currentIndex: 0,
      completed: [0],
      skipped: [],
      progressPercent: 50,
      supportedSectionCount: 1,
      lessonStillGrowing: false,
      generationActive: false,
      quality: {
        status: 'READY',
        score: 92,
        checks: [{ type: 'coverage', status: 'PASS', summary: '章节完整', detail: '每节都有原文。' }],
      },
      visualEnrichmentSummary: '已补入局部截图。',
      visualEnrichmentActive: false,
      mediaConsistency: {
        status: 'CONSISTENT',
        consistencyPercent: 100,
        checks: [{ type: 'script', status: 'PASS', summary: '同步完成', detail: '一致。' }],
      },
      mediaMode: 'TEXT',
      online: true,
      resuming: false,
      mediaModeAvailable,
      ...overrides,
    },
  })
}

describe('LessonReaderSidebar', () => {
  it('forwards chapter, media, and resume intents without owning reader state', async () => {
    const wrapper = mountSidebar()

    await wrapper.get('button[aria-pressed="false"]:not([disabled])').trigger('click')
    await wrapper.findAll('ol button')[1]!.trigger('click')
    await wrapper.get('button.bg-indigo').trigger('click')

    expect(wrapper.emitted('selectMediaMode')).toEqual([["VIDEO"]])
    expect(wrapper.emitted('selectSection')).toEqual([[1]])
    expect(wrapper.emitted('resume')).toHaveLength(1)
  })

  it('keeps unavailable media and offline resume visibly disabled', () => {
    const wrapper = mountSidebar({ online: false })

    expect(wrapper.get('button[aria-pressed="false"][disabled]').text()).toBe('语音')
    expect(wrapper.get('button.bg-indigo').attributes('disabled')).toBeDefined()
  })
})
