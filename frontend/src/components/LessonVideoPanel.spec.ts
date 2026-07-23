import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonVideoPanel from './LessonVideoPanel.vue'

const chapter = {
  position: 2,
  type: 'TURN',
  title: '走完第一回合',
  evidenceStatus: 'SUPPORTED' as const,
  visualKind: 'FLOW_DIAGRAM' as const,
  visualCaption: '依次拿牌、补牌、传给下家。',
  startMillis: 0,
  endMillis: 9_000,
  frames: [{ segmentPosition: 1, startMillis: 0, endMillis: 9_000, subtitle: '先从中间拿一张牌。', sourcePages: [5] }],
}

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(LessonVideoPanel, {
    props: {
      chapter,
      activeFrame: chapter.frames[0]!,
      chapters: [chapter, { ...chapter, position: 3, title: '计算分数' }],
      activeChapterIndex: 0,
      durationMillis: 20_000,
      playbackMillis: 5_000,
      playing: false,
      playbackRate: 1,
      audioAvailable: true,
      formatDuration: (millis: number) => `${millis}ms`,
      visualKindLabel: () => '顺着走',
      ...overrides,
    },
  })
}

describe('LessonVideoPanel', () => {
  it('renders storyboard context and forwards player intent to the reader', async () => {
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('走完第一回合')
    expect(wrapper.text()).toContain('规则书第 5 页')
    expect((wrapper.get('input[aria-label="视频播放位置"]').element as HTMLInputElement).value).toBe('5000')

    await wrapper.get('input[aria-label="视频播放位置"]').setValue('6500')
    await wrapper.findAll('button').find((button) => button.text() === '播放视频')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '重播画面')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '1×')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text().includes('3. 计算分数'))!.trigger('click')

    expect(wrapper.emitted('seek')).toEqual([[6500]])
    expect(wrapper.emitted('togglePlayback')).toHaveLength(1)
    expect(wrapper.emitted('replay')).toHaveLength(1)
    expect(wrapper.emitted('cycleRate')).toHaveLength(1)
    expect(wrapper.emitted('selectChapter')).toEqual([[1]])
  })

  it('does not render a video shell without both a chapter and an active frame', () => {
    const wrapper = mountPanel({ activeFrame: null })
    expect(wrapper.find('[aria-label="分章节视频"]').exists()).toBe(false)
  })
})
