import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonGenerationStatus from './LessonGenerationStatus.vue'
import { setLocale } from '@/lib/locale'
import { playerWorkStatus } from '@/lib/playerWorkStatus'

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
      terminalIssues: [],
      refreshFailed: false,
      finishedMessage: '',
      finishedStatus: null,
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

  it('keeps unknown, retry, and cited-draft states distinct', () => {
    const wrapper = mountStatus({ statusUnknown: true, refreshFailed: true, draftReady: true })

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('正在补充图片或核对细节')
    expect(wrapper.text()).toContain('正在确认后台生成状态')
    expect(wrapper.text()).toContain('完整基础讲解已经可用')
    expect(wrapper.text()).toContain('正在自动重试')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
  })

  it('renders complete, readable, failed, cancelled, and unavailable terminal facts without conflating them', async () => {
    const wrapper = mountStatus({
      active: false,
      finishedMessage: '讲解已经生成完成，全部章节都已载入。',
      finishedStatus: playerWorkStatus('GUIDE_COMPLETE', {
        capability: 'guide', readiness: 'complete', terminality: 'terminal', outcome: 'none',
      }, 'zh-CN'),
    })

    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('讲解完成')
    expect(wrapper.text()).toContain('讲解已经生成完成')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[role="status"]').attributes('aria-atomic')).toBe('true')

    await wrapper.setProps({
      finishedMessage: '已完成章节仍可阅读，可以稍后补全。',
      finishedStatus: playerWorkStatus('GUIDE_READABLE', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'none',
      }, 'zh-CN'),
    })
    const stopped = wrapper.get('[data-testid="player-work-status"]')
    expect(stopped.text()).toBe('基础讲解可读')
    expect(stopped.attributes('data-player-work-outcome')).toBe('none')
    expect(wrapper.get('[role="status"]').classes()).toContain('bg-amber-50')

    await wrapper.setProps({
      finishedMessage: '本轮讲解生成失败；已保留可读讲解草稿。',
      finishedStatus: playerWorkStatus('FAILED', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'failed',
      }, 'zh-CN'),
    })
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('失败')
    expect(wrapper.get('[data-testid="player-work-status"]').attributes('data-player-work-outcome')).toBe('failed')

    await wrapper.setProps({
      finishedMessage: '本轮讲解生成已取消；已保留可读讲解草稿。',
      finishedStatus: playerWorkStatus('CANCELLED', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'cancelled',
      }, 'zh-CN'),
    })
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('已取消')
    expect(wrapper.get('[data-testid="player-work-status"]').attributes('data-player-work-outcome')).toBe('cancelled')

    await wrapper.setProps({
      availableSectionCount: 0,
      finishedMessage: '本轮生成已经结束，但还没有可读章节。',
      finishedStatus: playerWorkStatus('NEEDS_ACTION', {
        capability: 'rulebook', readiness: 'unavailable', terminality: 'terminal', outcome: 'needs-action',
      }, 'zh-CN'),
    })
    expect(wrapper.get('[data-testid="player-work-status"]').text()).toBe('需要处理')
    expect(wrapper.get('[data-testid="player-work-status"]').attributes('data-player-work-readiness')).toBe('unavailable')
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

  it('retains the distinct cancelled terminal outcome in English', () => {
    setLocale('en')
    const wrapper = mountStatus({
      active: false,
      finishedMessage: 'This guide generation run was cancelled. A readable chapter draft is preserved.',
      finishedStatus: playerWorkStatus('CANCELLED', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'cancelled',
      }, 'en'),
    })

    const status = wrapper.get('[data-testid="player-work-status"]')
    expect(status.text()).toBe('Cancelled')
    expect(status.attributes('data-player-work-outcome')).toBe('cancelled')
    expect(wrapper.text()).toContain('A readable chapter draft is preserved')
  })

  it('keeps the exact unresolved chapter or visual failure visible after the run stops', () => {
    const wrapper = mountStatus({
      active: false,
      finishedMessage: '本轮已停止，已完成内容保留。',
      finishedStatus: playerWorkStatus('FAILED', {
        capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'failed',
      }, 'zh-CN'),
      terminalIssues: [{
        sequence: 9,
        outcome: 'REJECTED',
        text: '第 2 章“计分”的可选配图不可用；仅省略图片，已校验正文仍可阅读',
      }],
    })

    expect(wrapper.get('[data-testid="lesson-generation-terminal-issues"]').text())
      .toContain('第 2 章“计分”的可选配图不可用')
    expect(wrapper.text()).toContain('已校验正文仍可阅读')
  })
})
