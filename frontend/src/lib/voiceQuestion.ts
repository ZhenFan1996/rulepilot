const QUESTION_LIMIT = 800

export function normalizeVoiceTranscript(value: string) {
  return value.replace(/\s+/g, ' ').trim().slice(0, QUESTION_LIMIT).trimEnd()
}

export function mergeVoiceQuestion(current: string, transcript: string) {
  const normalizedCurrent = current.trim()
  const normalizedTranscript = normalizeVoiceTranscript(transcript)
  if (!normalizedTranscript) return normalizedCurrent
  const merged = normalizedCurrent
    ? `${normalizedCurrent}\n${normalizedTranscript}`
    : normalizedTranscript
  return merged.slice(0, QUESTION_LIMIT).trimEnd()
}
