export const BACKGROUND_WORK_CHANGED_EVENT = 'rulepilot:background-work-changed'

export function notifyBackgroundWorkChanged() {
  window.dispatchEvent(new Event(BACKGROUND_WORK_CHANGED_EVENT))
}
