export interface VisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
  sourceKind?: 'FULL_PAGE' | 'PAGE_REGION' | 'EMBEDDED_AUTHOR_IMAGE'
}

export interface LessonComprehensionReport {
  lessonId: string
  readyTaskCount: number
  taskCount: number
  canDoCount: number
  needsHelpCount: number
  readyVisualTaskCount: number
  visualAidRatedCount: number
  visualAidHelpfulCount: number
  visualAidHelpfulPercent: number | null
  visualAids: Array<{
    key: string
    label: string
    chapterPosition: number
    sourcePages: number[]
    visualFocus: VisualFocus
    result: 'NOT_RATED' | 'HELPFUL' | 'NOT_HELPFUL'
  }>
  tasks: Array<{
    type: 'PREPARE_TABLE' | 'PLAY_A_ROUND' | 'FINISH_GAME' | 'SCORE_GAME' | 'VERIFY_VISUAL_AID' | 'IDENTIFY_COMPONENTS' | 'COMPLETE_VISUAL_SETUP'
    label: string
    prompt: string
    readiness: 'READY' | 'MISSING_LESSON_CHECK' | 'MISSING_VISUAL_EVIDENCE'
    result: 'NOT_TRIED' | 'CAN_DO' | 'NEEDS_HELP'
    chapterPositions: number[]
    sourcePages: number[]
    visualFocus: VisualFocus | null
    visualAidResult: 'NOT_RATED' | 'HELPFUL' | 'NOT_HELPFUL'
  }>
}
