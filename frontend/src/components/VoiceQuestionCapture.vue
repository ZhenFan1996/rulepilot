<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

import { useLocale } from '@/lib/locale'
import { normalizeVoiceTranscript } from '@/lib/voiceQuestion'

defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  transcript: [text: string]
}>()

interface RecognitionAlternative {
  transcript: string
}

interface RecognitionResult {
  isFinal: boolean
  length: number
  [index: number]: RecognitionAlternative
}

interface RecognitionEventLike extends Event {
  resultIndex: number
  results: {
    length: number
    [index: number]: RecognitionResult
  }
}

interface RecognitionErrorEventLike extends Event {
  error: string
}

interface BrowserSpeechRecognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  onresult: ((event: RecognitionEventLike) => void) | null
  onerror: ((event: RecognitionErrorEventLike) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

type RecognitionConstructor = new () => BrowserSpeechRecognition

const browserWindow = window as typeof window & {
  SpeechRecognition?: RecognitionConstructor
  webkitSpeechRecognition?: RecognitionConstructor
}
const Recognition = browserWindow.SpeechRecognition ?? browserWindow.webkitSpeechRecognition
const supported = Boolean(Recognition)
const listening = ref(false)
const { locale, t } = useLocale()
const language = ref(locale.value === 'en' ? 'en-US' : 'zh-CN')
const interimText = ref('')
const finalText = ref('')
const errorMessage = ref('')
let recognition: BrowserSpeechRecognition | null = null
let recognitionFailed = false

const buttonLabel = computed(() => {
  if (!supported) return t('voice.unsupported')
  if (listening.value) return t('voice.stop')
  return t('voice.start')
})

function errorLabel(error: string) {
  const labels: Record<string, string> = {
    'not-allowed': t('voice.error.notAllowed'),
    'service-not-allowed': t('voice.error.service'),
    'audio-capture': t('voice.error.capture'),
    'network': t('voice.error.network'),
    'no-speech': t('voice.error.noSpeech'),
  }
  return labels[error] ?? t('voice.error.fallback')
}

function complete() {
  const transcript = normalizeVoiceTranscript(`${finalText.value} ${interimText.value}`)
  listening.value = false
  interimText.value = ''
  finalText.value = ''
  recognition = null
  if (!recognitionFailed && transcript) emit('transcript', transcript)
  recognitionFailed = false
}

function start() {
  if (!Recognition || listening.value) return
  errorMessage.value = ''
  recognitionFailed = false
  interimText.value = ''
  finalText.value = ''
  recognition = new Recognition()
  recognition.lang = language.value
  recognition.continuous = false
  recognition.interimResults = true
  recognition.maxAlternatives = 1
  recognition.onresult = (event) => {
    let interim = ''
    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const result = event.results[index]
      const text = result?.[0]?.transcript ?? ''
      if (result?.isFinal) finalText.value += ` ${text}`
      else interim += ` ${text}`
    }
    interimText.value = normalizeVoiceTranscript(interim)
  }
  recognition.onerror = (event) => {
    recognitionFailed = true
    errorMessage.value = errorLabel(event.error)
  }
  recognition.onend = complete
  try {
    recognition.start()
    listening.value = true
  } catch {
    recognition = null
    recognitionFailed = false
    errorMessage.value = t('voice.error.start')
  }
}

function toggle() {
  if (listening.value) recognition?.stop()
  else start()
}

onUnmounted(() => recognition?.abort())
</script>

<template>
  <div class="inline-flex max-w-full flex-col gap-2">
    <div class="flex flex-wrap items-center gap-2">
      <button
        type="button"
        class="min-h-11 rounded-xl border px-4 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-40"
        :class="listening ? 'border-red-300 bg-red-50 text-red-700' : 'border-copper/30 bg-copper/5 text-copper'"
        :disabled="disabled || !supported"
        :aria-pressed="listening"
        @click="toggle"
      >
        {{ buttonLabel }}
      </button>
      <select v-model="language" class="min-h-11 rounded-xl border border-ink/15 bg-canvas px-3 text-sm" :disabled="disabled || listening || !supported" :aria-label="t('voice.language')">
        <option value="zh-CN">{{ t('voice.chinese') }}</option>
        <option value="en-US">English</option>
      </select>
    </div>
    <p v-if="listening" class="max-w-md text-xs font-semibold leading-5 text-copper" role="status">
      {{ interimText || t('voice.listening') }}
    </p>
    <p v-else-if="errorMessage" class="max-w-md text-xs leading-5 text-red-700" role="alert">{{ errorMessage }}</p>
    <p v-else class="max-w-md text-xs leading-5 text-muted">
      {{ t('voice.privacy') }}
    </p>
  </div>
</template>
