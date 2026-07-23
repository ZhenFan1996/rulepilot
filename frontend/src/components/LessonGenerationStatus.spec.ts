import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonGenerationStatus from './LessonGenerationStatus.vue'

function mountStatus(overrides: Record<string, unknown> = {}) {
  return mount(LessonGenerationStatus, {
    props: {
      active: true,
      statusUnknown: false,
      statusText: '正在依据规则书编写“开始对局”',
      draftReady: false,
      availableSectionCount: 2,
      totalSectionCount: 5,
      elapsed: '1:12',
      processedChapterCount: 2,
      supportedChapterCount: 1,
      modelCallCount: 3,
      progressWidth: '40%',
      remainingTime: '按目前速度，剩余章节大约还需 1–2 分钟。',
      activities: [
        { sequence: 3, outcome: 'RUNNING', text: '正在依据规则书编写“开始对局”' },
        { sequence: 2, outcome: 'SUCCEEDED', text: '“开局准备”已经完成核对' },
      ],
      refreshFailed: false,
      finishedMessage: '',
      ...overrides,
    },
  })
}

describe('LessonGenerationStatus', () => {
  it('renders player-safe live progress without exposing raw activity details', () => {
    const wrapper = mountStatus()

    expect(wrapper.text()).toContain('正在依据规则书编写“开始对局”')
    expect(wrapper.text()).toContain('后台已处理 2/5 节，其中 1 节通过核对')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('2')
    expect(wrapper.text()).toContain('3 次模型调用')
    expect(wrapper.text()).not.toContain('composeTeachingSection')
  })

  it('keeps unknown, retry, cited-draft, and terminal states distinct', async () => {
    const wrapper = mountStatus({ statusUnknown: true, refreshFailed: true, draftReady: true })

    expect(wrapper.text()).toContain('正在确认后台生成状态')
    expect(wrapper.text()).toContain('完整基础讲解已经可用')
    expect(wrapper.text()).toContain('正在自动重试')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)

    await wrapper.setProps({ active: false, finishedMessage: '讲解已经生成完成，全部章节都已载入。' })
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
  })
})
