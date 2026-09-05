import type { AppLocale } from './locale'

const PLAYER_WORK_LABELS = {
  'zh-CN': {
    FINDING_GAME: '正在查找桌游',
    WAITING_FOR_PLAYER: '等待你继续',
    FINDING_RULEBOOK: '正在寻找规则书',
    ACQUIRING_RULEBOOK: '正在获取规则书',
    READING_RULEBOOK: '读取规则书',
    RULEBOOK_READY: '规则书可读',
    ORGANIZING_GUIDE: '正在组织讲解',
    GUIDE_READABLE: '已有章节可读',
    GUIDE_COMPLETE: '讲解完成',
    CHECKING_ANSWER: '正在核对回答',
    ANSWER_READY: '回答可读',
    NEEDS_ACTION: '需要处理',
    FAILED: '失败',
    CANCELLED: '已取消',
  },
  en: {
    FINDING_GAME: 'Finding games',
    WAITING_FOR_PLAYER: 'Waiting for you',
    FINDING_RULEBOOK: 'Finding rulebook',
    ACQUIRING_RULEBOOK: 'Getting rulebook',
    READING_RULEBOOK: 'Reading rulebook',
    RULEBOOK_READY: 'Rulebook ready',
    ORGANIZING_GUIDE: 'Organizing guide',
    GUIDE_READABLE: 'Chapters available',
    GUIDE_COMPLETE: 'Guide complete',
    CHECKING_ANSWER: 'Checking answer',
    ANSWER_READY: 'Answer ready',
    NEEDS_ACTION: 'Needs attention',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
  },
} as const

export type PlayerWorkStage = keyof typeof PLAYER_WORK_LABELS['zh-CN']
export type PlayerCapability = 'none' | 'rulebook' | 'guide' | 'answer'
export type PlayerReadiness = 'unavailable' | 'usable' | 'complete'
export type PlayerTerminality = 'waiting' | 'active' | 'terminal'
export type PlayerWorkOutcome = 'none' | 'needs-action' | 'failed' | 'cancelled'

interface PlayerWorkFacts {
  capability: PlayerCapability
  readiness: PlayerReadiness
  terminality: PlayerTerminality
  outcome: PlayerWorkOutcome
}

export interface PlayerWorkStatus extends PlayerWorkFacts {
  stage: PlayerWorkStage
  label: string
}

export type GuideWorkPhase = 'organizing' | 'readable' | 'complete' | 'needs-action'

/** Keeps the player label separate from capability, readiness, terminality, and recovery outcome. */
export function playerWorkStatus(
  stage: PlayerWorkStage,
  facts: PlayerWorkFacts,
  locale: AppLocale,
): PlayerWorkStatus {
  return { stage, label: PLAYER_WORK_LABELS[locale][stage], ...facts }
}

export function guideWorkStatus(
  phase: GuideWorkPhase,
  availableSectionCount: number,
  locale: AppLocale,
): PlayerWorkStatus {
  const resolvedPhase = phase === 'complete' && availableSectionCount === 0 ? 'needs-action' : phase
  const active = resolvedPhase === 'organizing' || resolvedPhase === 'readable'
  const complete = resolvedPhase === 'complete'
  return playerWorkStatus(
    resolvedPhase === 'organizing'
      ? 'ORGANIZING_GUIDE'
      : resolvedPhase === 'readable'
        ? 'GUIDE_READABLE'
        : complete ? 'GUIDE_COMPLETE' : 'NEEDS_ACTION',
    {
      capability: availableSectionCount > 0 ? 'guide' : 'rulebook',
      readiness: complete ? 'complete' : 'usable',
      terminality: active ? 'active' : 'terminal',
      outcome: resolvedPhase === 'needs-action' ? 'needs-action' : 'none',
    },
    locale,
  )
}
