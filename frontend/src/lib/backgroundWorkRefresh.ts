export const BACKGROUND_WORK_CHANGED_EVENT = 'rulepilot:background-work-changed'

export interface BackgroundWorkChangeDetail {
  dismissedImportIds?: string[]
  dismissedUploadedHandoffIds?: string[]
}

export function notifyBackgroundWorkChanged(detail: BackgroundWorkChangeDetail = {}) {
  window.dispatchEvent(new CustomEvent(BACKGROUND_WORK_CHANGED_EVENT, { detail }))
}
