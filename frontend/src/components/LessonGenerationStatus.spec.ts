import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonGenerationStatus from './LessonGenerationStatus.vue'
import { setLocale } from '@/lib/locale'

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
      progressWidth: '40%',
      remainingTime: '按目前速度，剩余章节大约还需 1–2 分钟。',
      activities: [
        { sequence: 3, outcome: 'RUNNING', text: '正在依据规则书编写“开始对局”' },
        { sequence: 2, outcome: 'SUCCEEDED', text: '“开局准备”已经完成核对' },
      ],
      refreshFailed: false,
      finishedMessage: '',
      finishedComplete: false,
      ...overrides,
    },
  })
}

describe('LessonGenerationStatus', () => {
  afterEach(() => setLocale('zh-CN'))

  it('renders player-safe live progress without exposing raw activity details', () => {
    const wrapper = mountStatus()

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('基础讲解可读')
    expect(status.attributes('data-player-work-readiness')).toBe('usable')
    expect(wrapper.text()).toContain('正在依据规则书编写“开始对局”')
    expect(wrapper.text()).toContain('后台已处理 2/5 节，其中 1 节通过核对')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('2')
    expect(wrapper.text()).not.toContain('模型调用')
    expect(wrapper.text()).not.toContain('composeTeachingSection')
  })

  it('keeps unknown, retry, cited-draft, and terminal states distinct', async () => {
    const wrapper = mountStatus({ statusUnknown: true, refreshFailed: true, draftReady: true })

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在补充图片或核对细节')
    expect(wrapper.text()).toContain('正在确认后台生成状态')
    expect(wrapper.text()).toContain('完整基础讲解已经可用')
    expect(wrapper.text()).toContain('正在自动重试')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)

    await wrapper.setProps({ active: false, finishedMessage: '讲解已经生成完成，全部章节都已载入。', finishedComplete: true })
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('讲解完成')
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)

    await wrapper.setProps({ finishedMessage: '已完成章节仍可阅读，可以稍后补全。', finishedComplete: false })
    const stopped = wrapper.get('[data-testid="player-work-status"]')
    expect(stopped.text()).toBe('需要处理')
    expect(stopped.attributes('data-player-work-outcome')).toBe('needs-action')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')

    await wrapper.setProps({ availableSectionCount: 0, finishedComplete: true })
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('需要处理')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')
  })

  it('localizes all deterministic progress chrome while retaining the supplied activity text', () => {
    setLocale('en')
    const wrapper = mountStatus({ statusText: 'Writing “开始对局” from the rulebook', remainingTime: 'About 2 minutes remaining.' })

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('Base guide ready')
    expect(wrapper.text()).toContain('Writing “开始对局” from the rulebook')
    expect(wrapper.text()).toContain('The complete guide is still being built')
    expect(wrapper.text()).toContain('2/5 chapters processed; 1 passed review')
    expect(wrapper.text()).not.toContain('model calls')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-label')).toBe('2 of 5 chapters processed')
  })
})
