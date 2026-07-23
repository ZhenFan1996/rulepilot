import { ref } from 'vue'

import type {
  ChapterVideo,
  LessonComprehensionReport,
  LessonQualityReport,
  MediaConsistencyReport,
  NarrationPlayback,
  NarrationScript,
  SpeechCue,
} from '@/composables/lessonSupportingContent'

interface LoadSupportingContentRequest {
  planId: string
  isCurrent: () => boolean
  narrationPositionKey: () => string
  requestLogin: () => Promise<unknown>
}

export function useLessonSupportingContent() {
  const quality = ref<LessonQualityReport | null>(null)
  const comprehension = ref<LessonComprehensionReport | null>(null)
  const comprehensionSaving = ref<string | null>(null)
  const comprehensionError = ref('')
  const narration = ref<NarrationScript | null>(null)
  const video = ref<ChapterVideo | null>(null)
  const mediaConsistency = ref<MediaConsistencyReport | null>(null)
  const mediaWarnings = ref<string[]>([])
  const audioAvailable = ref(false)
  const narrationProvider = ref('')
  const narrationDurationMillis = ref(0)
  const narrationCues = ref<SpeechCue[]>([])
  const narrationMillis = ref(0)
  const narrationPlaying = ref(false)
  const narrationRestoreTarget = ref<number | null>(null)

  function addMediaWarning(message: string) {
    if (!mediaWarnings.value.includes(message)) mediaWarnings.value.push(message)
  }

  function clearSupportingContent() {
    quality.value = null
    narration.value = null
    video.value = null
    mediaConsistency.value = null
    comprehension.value = null
    comprehensionSaving.value = null
    comprehensionError.value = ''
    mediaWarnings.value = []
    audioAvailable.value = false
    narrationProvider.value = ''
    narrationDurationMillis.value = 0
    narrationCues.value = []
    narrationMillis.value = 0
    narrationPlaying.value = false
    narrationRestoreTarget.value = null
  }

  async function optionalFetch(url: string) {
    try {
      return await fetch(url, { credentials: 'include' })
    } catch {
      return null
    }
  }

  async function loadSupportingContent(request: LoadSupportingContentRequest) {
    if (!request.isCurrent()) return
    clearSupportingContent()
    const [qualityResponse, comprehensionResponse, narrationResponse, videoResponse, consistencyResponse] = await Promise.all([
      optionalFetch(`/api/v1/teaching-plans/${request.planId}/illustrated-lessons/latest/quality`),
      optionalFetch(`/api/v1/teaching-plans/${request.planId}/comprehension`),
      optionalFetch(`/api/v1/teaching-plans/${request.planId}/narration/playback`),
      optionalFetch(`/api/v1/teaching-plans/${request.planId}/video`),
      optionalFetch(`/api/v1/teaching-plans/${request.planId}/media-consistency`),
    ])
    if (!request.isCurrent()) return
    if ([qualityResponse, comprehensionResponse, narrationResponse, videoResponse, consistencyResponse]
      .some((response) => response?.status === 401)) {
      await request.requestLogin()
      return
    }
    const [loadedQuality, loadedComprehension, loadedNarration, loadedVideo, loadedConsistency] = await Promise.all([
      qualityResponse?.ok ? qualityResponse.json() as Promise<LessonQualityReport> : Promise.resolve(null),
      comprehensionResponse?.ok ? comprehensionResponse.json() as Promise<LessonComprehensionReport> : Promise.resolve(null),
      narrationResponse?.ok ? narrationResponse.json() as Promise<NarrationPlayback> : Promise.resolve(null),
      videoResponse?.ok ? videoResponse.json() as Promise<ChapterVideo> : Promise.resolve(null),
      consistencyResponse?.ok ? consistencyResponse.json() as Promise<MediaConsistencyReport> : Promise.resolve(null),
    ])
    if (!request.isCurrent()) return
    if (loadedQuality) quality.value = loadedQuality
    else addMediaWarning('讲解诊断暂不可用，不影响继续阅读。')
    if (loadedComprehension) comprehension.value = loadedComprehension
    else comprehensionError.value = '学习检查暂时无法读取，不影响继续看讲解。'
    if (loadedNarration) {
      narration.value = loadedNarration.script
      narrationProvider.value = loadedNarration.provider
      narrationDurationMillis.value = loadedNarration.durationMillis
      narrationCues.value = loadedNarration.cues
      audioAvailable.value = true
    } else {
      addMediaWarning('语音暂不可用，已保留完整图文讲解。')
    }
    if (loadedVideo) video.value = loadedVideo
    else addMediaWarning('视频暂不可用，可继续使用图文或语音讲解。')
    mediaConsistency.value = loadedConsistency
    const restoredNarration = Number(localStorage.getItem(request.narrationPositionKey()))
    if (Number.isFinite(restoredNarration) && restoredNarration >= 0 && restoredNarration < narrationDurationMillis.value) {
      narrationRestoreTarget.value = restoredNarration
      narrationMillis.value = restoredNarration
    }
  }

  return {
    quality,
    comprehension,
    comprehensionSaving,
    comprehensionError,
    narration,
    video,
    mediaConsistency,
    mediaWarnings,
    audioAvailable,
    narrationProvider,
    narrationDurationMillis,
    narrationCues,
    narrationMillis,
    narrationPlaying,
    narrationRestoreTarget,
    addMediaWarning,
    clearSupportingContent,
    loadSupportingContent,
  }
}
