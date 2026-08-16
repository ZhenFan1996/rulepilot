import { describe, expect, it } from 'vitest'

import {
  guideWorkStatus,
  PLAYER_WORK_LABELS,
  playerWorkStatus,
  type PlayerWorkFacts,
} from './playerWorkStatus'

describe('player-facing work status', () => {
  it('uses one bilingual vocabulary without implementation terminology', () => {
    expect(PLAYER_WORK_LABELS['zh-CN']).toEqual({
      FINDING_GAME: '正在查找桌游',
      WAITING_FOR_PLAYER: '等待你继续',
      FINDING_RULEBOOK: '正在寻找规则书',
      ACQUIRING_RULEBOOK: '正在获取规则书',
      READING_RULEBOOK: '读取规则书',
      RULEBOOK_READY: '规则书可读',
      ORGANIZING_GUIDE: '正在组织讲解',
      GUIDE_READABLE: '基础讲解可读',
      REVIEWING_GUIDE: '正在补充图片或核对细节',
      GUIDE_COMPLETE: '讲解完成',
      CHECKING_ANSWER: '正在核对回答',
      ANSWER_READY: '回答可读',
      NEEDS_ACTION: '需要处理',
      FAILED: '失败',
      CANCELLED: '已取消',
    })
    expect(PLAYER_WORK_LABELS.en).toEqual({
      FINDING_GAME: 'Finding games',
      WAITING_FOR_PLAYER: 'Waiting for you',
      FINDING_RULEBOOK: 'Finding rulebook',
      ACQUIRING_RULEBOOK: 'Getting rulebook',
      READING_RULEBOOK: 'Reading rulebook',
      RULEBOOK_READY: 'Rulebook ready',
      ORGANIZING_GUIDE: 'Organizing guide',
      GUIDE_READABLE: 'Base guide ready',
      REVIEWING_GUIDE: 'Adding visuals or checking details',
      GUIDE_COMPLETE: 'Guide complete',
      CHECKING_ANSWER: 'Checking answer',
      ANSWER_READY: 'Answer ready',
      NEEDS_ACTION: 'Needs attention',
      FAILED: 'Failed',
      CANCELLED: 'Cancelled',
    })
    expect(JSON.stringify(PLAYER_WORK_LABELS)).not.toMatch(/Agent|模型|model|工具|tool|embedding|chunk|内部枚举/i)
  })

  it('keeps capability, readiness, terminality, and outcome orthogonal', () => {
    const activeFacts: PlayerWorkFacts = {
      capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    }
    const stoppedFacts: PlayerWorkFacts = {
      capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    }

    const active = playerWorkStatus('GUIDE_READABLE', activeFacts, 'zh-CN')
    const stopped = playerWorkStatus('GUIDE_READABLE', stoppedFacts, 'zh-CN')

    expect(active.label).toBe('基础讲解可读')
    expect(stopped.label).toBe('基础讲解可读')
    expect(active).toMatchObject(activeFacts)
    expect(stopped).toMatchObject(stoppedFacts)
  })

  it('derives guide generation facts consistently for active, readable, and stopped work', () => {
    expect(guideWorkStatus('organizing', 0, 'zh-CN')).toMatchObject({
      stage: 'ORGANIZING_GUIDE', capability: 'rulebook', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('reviewing', 2, 'zh-CN')).toMatchObject({
      stage: 'REVIEWING_GUIDE', capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('readable', 1, 'zh-CN')).toMatchObject({
      stage: 'GUIDE_READABLE', capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('complete', 2, 'zh-CN')).toMatchObject({
      stage: 'GUIDE_COMPLETE', capability: 'guide', readiness: 'complete', terminality: 'terminal', outcome: 'none',
    })
    expect(guideWorkStatus('needs-action', 2, 'zh-CN')).toMatchObject({
      stage: 'NEEDS_ACTION', capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    })
    expect(guideWorkStatus('complete', 0, 'zh-CN')).toMatchObject({
      stage: 'NEEDS_ACTION', capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    })
  })
})
