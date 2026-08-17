export function normalizeVoiceTranscript(value: string) {
  return value.replace(/\s+/g, ' ').trim()
}

export function mergeVoiceQuestion(current: string, transcript: string) {
  const normalizedCurrent = current.trim()
  const normalizedTranscript = normalizeVoiceTranscript(transcript)
  if (!normalizedTranscript) return normalizedCurrent
  const merged = normalizedCurrent
    ? `${normalizedCurrent}\n${normalizedTranscript}`
    : normalizedTranscript
  return merged.trimEnd()
}
