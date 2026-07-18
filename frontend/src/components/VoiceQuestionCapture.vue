<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

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
const language = ref(navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US')
const interimText = ref('')
const finalText = ref('')
const errorMessage = ref('')
let recognition: BrowserSpeechRecognition | null = null
let recognitionFailed = false

const buttonLabel = computed(() => {
  if (!supported) return '当前浏览器不支持语音输入'
  if (listening.value) return '停止并使用语音文字'
  return '用语音输入问题'
})

function errorLabel(error: string) {
  const labels: Record<string, string> = {
    'not-allowed': '未获得麦克风权限；你仍可继续键盘输入。',
    'service-not-allowed': '浏览器语音服务不可用；你仍可继续键盘输入。',
    'audio-capture': '没有找到可用麦克风。',
    'network': '语音服务暂时无法连接。',
    'no-speech': '没有听到清晰语音，请靠近麦克风重试。',
  }
  return labels[error] ?? '语音识别没有完成，请重试或改用键盘。'
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
    errorMessage.value = '无法启动麦克风，请重试或改用键盘。'
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
      <select v-model="language" class="min-h-11 rounded-xl border border-ink/15 bg-canvas px-3 text-sm" :disabled="disabled || listening || !supported" aria-label="语音识别语言">
        <option value="zh-CN">普通话</option>
        <option value="en-US">English</option>
      </select>
    </div>
    <p v-if="listening" class="max-w-md text-xs font-semibold leading-5 text-copper" role="status">
      {{ interimText || '正在聆听…说完后再次点按按钮。' }}
    </p>
    <p v-else-if="errorMessage" class="max-w-md text-xs leading-5 text-red-700" role="alert">{{ errorMessage }}</p>
    <p v-else class="max-w-md text-xs leading-5 text-ink/45">
      音频不由 RulePilot 保存；浏览器或操作系统的语音服务可能处理音频，可随时改用键盘。
    </p>
  </div>
</template>
