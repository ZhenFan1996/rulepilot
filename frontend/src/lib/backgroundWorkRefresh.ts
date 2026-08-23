export const BACKGROUND_WORK_CHANGED_EVENT = 'rulepilot:background-work-changed'

export interface BackgroundWorkChangeDetail {
  dismissedImportIds?: string[]
  dismissedUploadedHandoffIds?: string[]
  dismissedTeachingRunIds?: string[]
  importJob?: unknown
}

export function notifyBackgroundWorkChanged(detail: BackgroundWorkChangeDetail = {}) {
  window.dispatchEvent(new CustomEvent(BACKGROUND_WORK_CHANGED_EVENT, { detail }))
}
