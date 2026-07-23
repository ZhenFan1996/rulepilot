import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonNarrationPanel from './LessonNarrationPanel.vue'

const chapter = {
  position: 3,
  type: 'SCORING',
  title: '最后计分',
  supported: true,
  segments: [
    { position: 1, text: '先完成基础分。', sourcePages: [12] },
    { position: 2, text: '再计算额外奖励。', sourcePages: [13] },
  ],
}

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(LessonNarrationPanel, {
    props: {
      visible: true,
      chapter,
      activeCue: { chapterPosition: 3, segmentPosition: 2, startMillis: 5_000, endMillis: 9_000 },
      durationMillis: 12_000,
      playbackMillis: 6_000,
      playing: false,
      playbackRate: 1,
      formatDuration: (millis: number) => `${millis}ms`,
      ...overrides,
    },
  })
}

describe('LessonNarrationPanel', () => {
  it('renders synchronized script context and forwards media intents', async () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('规则书第 12 页')
    expect(wrapper.findAll('li')[1]!.classes()).toContain('bg-copper/10')
    await wrapper.findAll('button').find((button) => button.text() === '从本段播放')!.trigger('click')
    await wrapper.get('input[aria-label="解说播放位置"]').setValue('7500')
    await wrapper.findAll('button').find((button) => button.text() === '播放')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '重播本段')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '1×')!.trigger('click')

    expect(wrapper.emitted('seekSegment')).toEqual([[1]])
    expect(wrapper.emitted('seek')).toEqual([[7500]])
    expect(wrapper.emitted('togglePlayback')).toHaveLength(1)
    expect(wrapper.emitted('replay')).toHaveLength(1)
    expect(wrapper.emitted('cycleRate')).toHaveLength(1)
  })

  it('does not render narration controls when there is no chapter', () => {
    const wrapper = mountPanel({ chapter: null })
    expect(wrapper.find('[aria-label="同步字幕"]').exists()).toBe(false)
  })
})
