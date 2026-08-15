import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LessonReaderSidebar from './LessonReaderSidebar.vue'
import { setLocale } from '@/lib/locale'

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
      resumeError: '',
      mediaModeAvailable,
      ...overrides,
    },
  })
}

describe('LessonReaderSidebar', () => {
  afterEach(() => setLocale('zh-CN'))

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

  it('keeps a resume failure beside the recovery action', () => {
    const wrapper = mountSidebar({ resumeError: 'Could not continue this guide.' })

    expect(wrapper.text()).toContain('Could not continue this guide.')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.get('button.bg-indigo').text()).toContain('继续核对细节')
  })

  it('shows an explicit missing-source dependency as a blocked quality check', () => {
    const wrapper = mountSidebar({
      quality: {
        status: 'BLOCKED',
        score: 40,
        checks: [{
          type: 'SOURCE_AVAILABILITY',
          status: 'FAIL',
          summary: '当前规则书还缺 1 份被明确引用的资料',
          detail: '第 1 页指向 Quick Start Guide；当前文档不包含开局步骤。',
        }],
      },
    })

    expect(wrapper.text()).toContain('暂不能确认')
    expect(wrapper.text()).toContain('当前规则书还缺 1 份被明确引用的资料')
    expect(wrapper.text()).toContain('第 1 页指向 Quick Start Guide')
    expect(wrapper.text()).toContain('当前文档不包含开局步骤')
    expect(wrapper.text()).not.toContain('完整基础讲解可以使用')
    expect(wrapper.text()).not.toContain('你可以先开桌')
  })

  it('localizes reader controls and status chrome without changing emitted intents', () => {
    setLocale('en')
    const wrapper = mountSidebar()

    expect(wrapper.attributes('aria-label')).toBe('Guide chapters')
    expect(wrapper.text()).toContain('Guide contents')
    expect(wrapper.text()).toContain('Starter guide ready')
    expect(wrapper.text()).toContain('Guide check')
    expect(wrapper.text()).toContain('Focused visuals are ready')
    expect(wrapper.text()).toContain('Reading, audio, and video')
    expect(wrapper.text()).toContain('Reading')
    expect(wrapper.text()).toContain('Audio')
    expect(wrapper.text()).toContain('Video')
  })
})
