export const TEACHING_LAUNCHED_EVENT = 'rulepilot:teaching-launched'

export interface TeachingLaunch {
  assistantRunId: string
  state: string
  reused: boolean
}

export interface TeachingLaunchDetail {
  planId: string
  runId: string
  gameTitle?: string
}

export function notifyTeachingLaunched(detail: TeachingLaunchDetail) {
  window.dispatchEvent(new CustomEvent<TeachingLaunchDetail>(TEACHING_LAUNCHED_EVENT, { detail }))
}

export function teachingLaunchDetail(event: Event): TeachingLaunchDetail | null {
  const detail = (event as CustomEvent<Partial<TeachingLaunchDetail>>).detail
  if (!detail || !bounded(detail.planId, 64) || !bounded(detail.runId, 64)) return null
  return {
    planId: detail.planId,
    runId: detail.runId,
    gameTitle: bounded(detail.gameTitle, 160) ? detail.gameTitle : undefined,
  }
}

function bounded(value: unknown, _maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0
}
