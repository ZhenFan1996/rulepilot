export interface VisualFocus {
  pageNumber: number
  label: string
  x: number
  y: number
  width: number
  height: number
}

export interface LessonQualityReport {
  status: 'READY' | 'NEEDS_REVIEW' | 'BLOCKED'
  score: number
  checks: Array<{
    type: string
    status: 'PASS' | 'FAIL' | 'NOT_EVALUATED'
    summary: string
    detail: string
  }>
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

export interface NarrationScript {
  id: string
  status: 'READY' | 'INCOMPLETE'
  chapters: Array<{
    position: number
    type: string
    title: string
    supported: boolean
    segments: Array<{ position: number; text: string; sourcePages: number[] }>
  }>
}

export interface SpeechCue {
  chapterPosition: number
  segmentPosition: number
  startMillis: number
  endMillis: number
}

export interface NarrationPlayback {
  script: NarrationScript
  provider: string
  durationMillis: number
  cues: SpeechCue[]
}

export interface VideoChapter {
  position: number
  type: string
  title: string
  evidenceStatus: 'SUPPORTED' | 'INSUFFICIENT_EVIDENCE'
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  startMillis: number
  endMillis: number
  frames: Array<{
    segmentPosition: number
    startMillis: number
    endMillis: number
    subtitle: string
    sourcePages: number[]
  }>
}

export interface ChapterVideo {
  id: string
  status: 'READY' | 'INCOMPLETE'
  durationMillis: number
  chapters: VideoChapter[]
}

export interface MediaConsistencyReport {
  status: 'CONSISTENT' | 'INCONSISTENT'
  consistencyPercent: number
  checks: Array<{
    type: string
    status: 'PASS' | 'FAIL'
    summary: string
    detail: string
  }>
}
