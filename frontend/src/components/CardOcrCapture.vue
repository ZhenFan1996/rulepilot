<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'

import { useModalFocus } from '@/composables/useModalFocus'
import { normalizeCardText } from '@/lib/cardOcr'
import { useLocale } from '@/lib/locale'

const emit = defineEmits<{
  close: []
  recognized: [text: string]
}>()

type ProgressStatus =
  | 'cardOcr.status.waitingForPhoto'
  | 'cardOcr.status.ready'
  | 'cardOcr.status.starting'
  | 'cardOcr.status.loadingCore'
  | 'cardOcr.status.initializing'
  | 'cardOcr.status.loadingLanguage'
  | 'cardOcr.status.preparing'
  | 'cardOcr.status.recognizing'
  | 'cardOcr.status.processing'
  | 'cardOcr.status.completed'
  | 'cardOcr.status.incomplete'

type ErrorStatus =
  | ''
  | 'cardOcr.error.fileType'
  | 'cardOcr.error.fileSize'
  | 'cardOcr.error.noText'
  | 'cardOcr.error.failed'

const { locale, t } = useLocale()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const previewUrl = ref('')
const recognizedText = ref('')
const errorStatus = ref<ErrorStatus>('')
const progress = ref(0)
const progressStatus = ref<ProgressStatus>('cardOcr.status.waitingForPhoto')
const recognizing = ref(false)
const dialog = ref<HTMLElement | null>(null)
const language = ref<'chi_sim+eng' | 'eng'>(locale.value === 'en' ? 'eng' : 'chi_sim+eng')
let activeWorker: Awaited<ReturnType<typeof import('tesseract.js')['createWorker']>> | null = null

useModalFocus({ dialog, open: () => true, requestClose: close })

const progressPercent = computed(() => Math.round(progress.value * 100))
const canUseText = computed(() => normalizeCardText(recognizedText.value).length > 0)
const errorMessage = computed(() => errorStatus.value ? t(errorStatus.value) : '')
const progressLabel = computed(() => t(progressStatus.value))

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
  errorStatus.value = ''
  recognizedText.value = ''
  progress.value = 0
  progressStatus.value = 'cardOcr.status.ready'

  if (!file) return
  if (!file.type.startsWith('image/')) {
    selectedFile.value = null
    replacePreview(null)
    errorStatus.value = 'cardOcr.error.fileType'
    return
  }
  if (file.size > 12 * 1024 * 1024) {
    selectedFile.value = null
    replacePreview(null)
    errorStatus.value = 'cardOcr.error.fileSize'
    return
  }
  selectedFile.value = file
  replacePreview(file)
}

function progressStatusFor(status: string): ProgressStatus {
  const labels: Record<string, ProgressStatus> = {
    'loading tesseract core': 'cardOcr.status.loadingCore',
    'initializing tesseract': 'cardOcr.status.initializing',
    'loading language traineddata': 'cardOcr.status.loadingLanguage',
    'initializing api': 'cardOcr.status.preparing',
    'recognizing text': 'cardOcr.status.recognizing',
  }
  return labels[status] ?? 'cardOcr.status.processing'
}

async function recognize() {
  if (!selectedFile.value || recognizing.value) return
  recognizing.value = true
  errorStatus.value = ''
  recognizedText.value = ''
  progress.value = 0
  progressStatus.value = 'cardOcr.status.starting'

  try {
    const { createWorker, OEM, PSM } = await import('tesseract.js')
    activeWorker = await createWorker(language.value, OEM.LSTM_ONLY, {
      workerPath: '/ocr-assets/v7/worker.min.js',
      corePath: '/ocr-assets/v7/tesseract-core-lstm.wasm.js',
      langPath: '/ocr-assets/v7/lang',
      logger(message) {
        progress.value = Number.isFinite(message.progress) ? message.progress : 0
        progressStatus.value = progressStatusFor(message.status)
      },
    })
    await activeWorker.setParameters({
      tessedit_pageseg_mode: PSM.SPARSE_TEXT,
      preserve_interword_spaces: '1',
    })
    const result = await activeWorker.recognize(selectedFile.value, { rotateAuto: true })
    recognizedText.value = normalizeCardText(result.data.text)
    progress.value = 1
    progressStatus.value = 'cardOcr.status.completed'
    if (!recognizedText.value) {
      errorStatus.value = 'cardOcr.error.noText'
    }
  } catch {
    progress.value = 0
    progressStatus.value = 'cardOcr.status.incomplete'
    errorStatus.value = 'cardOcr.error.failed'
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
      ref="dialog"
      tabindex="-1"
      class="max-h-[92vh] w-full overflow-y-auto rounded-t-[2rem] bg-paper p-5 text-ink elevation-2xl sm:max-w-2xl sm:rounded-[2rem] sm:p-7"
      role="dialog"
      aria-modal="true"
      aria-labelledby="card-ocr-title"
    >
      <div class="flex items-start justify-between gap-4">
        <div>
          <p class="text-xs font-semibold text-copper">{{ t('cardOcr.eyebrow') }}</p>
          <h3 id="card-ocr-title" class="mt-2 font-display text-3xl font-semibold">{{ t('cardOcr.title') }}</h3>
          <p class="mt-2 text-sm leading-6 text-ink/60">{{ t('cardOcr.description') }}</p>
        </div>
        <button data-modal-initial-focus class="grid size-11 shrink-0 place-items-center rounded-full border border-ink/15 text-xl" :disabled="recognizing" :aria-label="t('cardOcr.close')" @click="close">×</button>
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
          <img v-if="previewUrl" :src="previewUrl" :alt="t('cardOcr.previewAlt')" class="h-full w-full object-contain">
          <span v-else class="m-auto max-w-48 px-5 text-sm font-semibold leading-6 text-indigo">{{ t('cardOcr.choosePhoto') }}</span>
          <span v-if="previewUrl" class="absolute inset-x-3 bottom-3 rounded-xl bg-black/65 px-3 py-2 text-xs font-semibold text-white">{{ t('cardOcr.changePhoto') }}</span>
        </button>

        <div class="flex flex-col">
          <label for="ocr-language" class="text-sm font-semibold">{{ t('cardOcr.language') }}</label>
          <select id="ocr-language" v-model="language" class="mt-2 min-h-11 rounded-xl border border-ink/15 bg-canvas px-3" :disabled="recognizing">
            <option value="chi_sim+eng">{{ t('cardOcr.language.bilingual') }}</option>
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
            {{ recognizing ? t('cardOcr.recognizing') : t('cardOcr.recognize') }}
          </button>
          <p class="mt-3 text-xs leading-5 text-ink/45">{{ t('cardOcr.downloadHint') }}</p>
        </div>
      </div>

      <p v-if="errorMessage" class="mt-5 rounded-2xl bg-red-50 px-4 py-3 text-sm leading-6 text-red-700" role="alert">{{ errorMessage }}</p>

      <div v-if="recognizedText" class="mt-5">
        <label for="recognized-card-text" class="text-sm font-semibold">{{ t('cardOcr.review') }}</label>
        <textarea
          id="recognized-card-text"
          v-model="recognizedText"
          rows="6"
          class="mt-2 w-full resize-y rounded-2xl border border-ink/15 bg-canvas px-4 py-3 leading-7 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10"
        />
        <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
          <p class="text-xs text-ink/45">{{ t('cardOcr.reviewHint', { count: recognizedText.length }) }}</p>
          <button type="button" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40" :disabled="!canUseText" @click="useRecognizedText">{{ t('cardOcr.useText') }}</button>
        </div>
      </div>
    </section>
  </div>
</template>
