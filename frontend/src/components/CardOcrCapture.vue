<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

import { normalizeCardText } from '@/lib/cardOcr'

const emit = defineEmits<{
  close: []
  recognized: [text: string]
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const previewUrl = ref('')
const recognizedText = ref('')
const errorMessage = ref('')
const progress = ref(0)
const progressLabel = ref('等待选择卡牌照片')
const recognizing = ref(false)
const language = ref<'chi_sim+eng' | 'eng'>('chi_sim+eng')
let activeWorker: Awaited<ReturnType<typeof import('tesseract.js')['createWorker']>> | null = null

const progressPercent = computed(() => Math.round(progress.value * 100))
const canUseText = computed(() => normalizeCardText(recognizedText.value).length > 0)

function replacePreview(file: File | null) {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = file ? URL.createObjectURL(file) : ''
}

function chooseFile() {
  fileInput.value?.click()
}

function onFileSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  errorMessage.value = ''
  recognizedText.value = ''
  progress.value = 0
  progressLabel.value = '等待开始识别'

  if (!file) return
  if (!file.type.startsWith('image/')) {
    selectedFile.value = null
    replacePreview(null)
    errorMessage.value = '请选择 JPG、PNG、HEIC 或其他图片文件。'
    return
  }
  if (file.size > 12 * 1024 * 1024) {
    selectedFile.value = null
    replacePreview(null)
    errorMessage.value = '卡牌照片不能超过 12 MiB。'
    return
  }
  selectedFile.value = file
  replacePreview(file)
}

function readableStatus(status: string) {
  const labels: Record<string, string> = {
    'loading tesseract core': '正在载入本地 OCR 核心',
    'initializing tesseract': '正在初始化文字识别',
    'loading language traineddata': '正在载入中英文识别数据',
    'initializing api': '正在准备识别模型',
    'recognizing text': '正在识别卡牌文字',
  }
  return labels[status] ?? '正在处理卡牌照片'
}

async function recognize() {
  if (!selectedFile.value || recognizing.value) return
  recognizing.value = true
  errorMessage.value = ''
  recognizedText.value = ''
  progress.value = 0
  progressLabel.value = '正在启动浏览器 OCR'

  try {
    const { createWorker, OEM, PSM } = await import('tesseract.js')
    activeWorker = await createWorker(language.value, OEM.LSTM_ONLY, {
      workerPath: '/ocr-assets/v7/worker.min.js',
      corePath: '/ocr-assets/v7/tesseract-core-lstm.wasm.js',
      langPath: '/ocr-assets/v7/lang',
      logger(message) {
        progress.value = Number.isFinite(message.progress) ? message.progress : 0
        progressLabel.value = readableStatus(message.status)
      },
    })
    await activeWorker.setParameters({
      tessedit_pageseg_mode: PSM.SPARSE_TEXT,
      preserve_interword_spaces: '1',
    })
    const result = await activeWorker.recognize(selectedFile.value, { rotateAuto: true })
    recognizedText.value = normalizeCardText(result.data.text)
    progress.value = 1
    progressLabel.value = '识别完成，请核对文字'
    if (!recognizedText.value) {
      throw new Error('没有识别出清晰文字，请让卡牌充满画面并避免反光后重试。')
    }
  } catch (error) {
    progress.value = 0
    progressLabel.value = '识别未完成'
    errorMessage.value = error instanceof Error
      ? error.message
      : '卡牌识别失败，请检查网络或换一张更清晰的照片。'
  } finally {
    await activeWorker?.terminate().catch(() => undefined)
    activeWorker = null
    recognizing.value = false
  }
}

function useRecognizedText() {
  const text = normalizeCardText(recognizedText.value)
  if (!text) return
  emit('recognized', text)
}

function close() {
  if (!recognizing.value) emit('close')
}

onUnmounted(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  void activeWorker?.terminate()
})
</script>

<template>
  <div class="fixed inset-0 z-50 grid place-items-end bg-black/55 p-0 sm:place-items-center sm:p-6" role="presentation" @click.self="close">
    <section
      class="max-h-[92vh] w-full overflow-y-auto rounded-t-[2rem] bg-paper p-5 text-ink shadow-2xl sm:max-w-2xl sm:rounded-[2rem] sm:p-7"
      role="dialog"
      aria-modal="true"
      aria-labelledby="card-ocr-title"
    >
      <div class="flex items-start justify-between gap-4">
        <div>
          <p class="eyebrow">LOCAL CARD OCR</p>
          <h3 id="card-ocr-title" class="mt-2 font-display text-3xl font-semibold">拍照识别卡牌文字</h3>
          <p class="mt-2 text-sm leading-6 text-ink/60">识别在浏览器内完成；照片不会上传到 RulePilot 或第三方服务。</p>
        </div>
        <button class="grid size-11 shrink-0 place-items-center rounded-full border border-ink/15 text-xl" :disabled="recognizing" aria-label="关闭卡牌识别" @click="close">×</button>
      </div>

      <input
        ref="fileInput"
        class="sr-only"
        type="file"
        accept="image/*"
        capture="environment"
        @change="onFileSelected"
      >

      <div class="mt-6 grid gap-5 sm:grid-cols-[1.05fr_0.95fr]">
        <button
          type="button"
          class="relative grid min-h-64 overflow-hidden rounded-3xl border-2 border-dashed border-indigo/30 bg-indigo/5 text-center focus:outline-none focus:ring-4 focus:ring-indigo/15"
          :disabled="recognizing"
          @click="chooseFile"
        >
          <img v-if="previewUrl" :src="previewUrl" alt="待识别的卡牌照片" class="h-full w-full object-contain">
          <span v-else class="m-auto max-w-48 px-5 text-sm font-semibold leading-6 text-indigo">点按拍摄卡牌，或从设备中选择已有照片</span>
          <span v-if="previewUrl" class="absolute inset-x-3 bottom-3 rounded-xl bg-black/65 px-3 py-2 text-xs font-semibold text-white">点按更换照片</span>
        </button>

        <div class="flex flex-col">
          <label for="ocr-language" class="text-sm font-semibold">卡牌文字</label>
          <select id="ocr-language" v-model="language" class="mt-2 min-h-11 rounded-xl border border-ink/15 bg-canvas px-3" :disabled="recognizing">
            <option value="chi_sim+eng">简体中文 + English</option>
            <option value="eng">English</option>
          </select>

          <div class="mt-4 rounded-2xl bg-canvas p-4" aria-live="polite">
            <div class="flex items-center justify-between gap-3 text-xs font-semibold">
              <span>{{ progressLabel }}</span>
              <span>{{ progressPercent }}%</span>
            </div>
            <div class="mt-2 h-2 overflow-hidden rounded-full bg-ink/10">
              <div class="h-full bg-copper transition-all" :style="{ width: `${progressPercent}%` }" />
            </div>
          </div>

          <button
            type="button"
            class="mt-4 min-h-12 rounded-xl bg-copper px-5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="!selectedFile || recognizing"
            @click="recognize"
          >
            {{ recognizing ? '正在本地识别…' : '开始识别文字' }}
          </button>
          <p class="mt-3 text-xs leading-5 text-ink/45">首次使用需联网下载 OCR 运行文件和语言数据；完成后浏览器可复用缓存。</p>
        </div>
      </div>

      <p v-if="errorMessage" class="mt-5 rounded-2xl bg-red-50 px-4 py-3 text-sm leading-6 text-red-700" role="alert">{{ errorMessage }}</p>

      <div v-if="recognizedText" class="mt-5">
        <label for="recognized-card-text" class="text-sm font-semibold">核对识别结果</label>
        <textarea
          id="recognized-card-text"
          v-model="recognizedText"
          rows="6"
          maxlength="620"
          class="mt-2 w-full resize-y rounded-2xl border border-ink/15 bg-canvas px-4 py-3 leading-7 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10"
        />
        <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
          <p class="text-xs text-ink/45">{{ recognizedText.length }}/620 · 请先修正错字，再交给规则检索。</p>
          <button type="button" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40" :disabled="!canUseText" @click="useRecognizedText">带入本节提问</button>
        </div>
      </div>
    </section>
  </div>
</template>
