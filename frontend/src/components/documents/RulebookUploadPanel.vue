<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useLocale } from '@/lib/locale'
import RulebookIdentityConfirmation from './RulebookIdentityConfirmation.vue'
import type {
  EditionOption,
  PhotographedPage,
  RulebookDiscoveryIdentity,
  RulebookImportIdentitySource,
} from './types'

defineProps<{
  file: File | null
  photographedPages: PhotographedPage[]
  preparingPhotos: boolean
  intakeControlsDisabled: boolean
  intakeDraftAreas: string[]
  intakeDraftCopy: {
    status: (areas: string) => string
    memoryOnly: string
  }
  editionOptions: EditionOption[]
  identityTarget: RulebookDiscoveryIdentity | null
  identitySourceContext: RulebookDiscoveryIdentity | null
  identitySource: RulebookImportIdentitySource | null
  modelConfigurationAvailable: boolean
  visualVisionCapable: boolean
  canImportOfficial: boolean
  importingOfficial: boolean
  canUpload: boolean
  preparingVersionId: string
  uploading: boolean
}>()

const title = defineModel<string>('title', { required: true })
const officialSourceUrl = defineModel<string>('officialSourceUrl', { required: true })
const officialImportIdentityConfirmed = defineModel<boolean>('officialImportIdentityConfirmed', { required: true })
const officialImportRightsConfirmed = defineModel<boolean>('officialImportRightsConfirmed', { required: true })
const editionId = defineModel<string>('editionId', { required: true })
const learningGoal = defineModel<string>('learningGoal', { required: true })
const sourceType = defineModel<string>('sourceType', { required: true })

const emit = defineEmits<{
  submit: []
  'select-file': [event: Event]
  'add-photos': [event: Event]
  'move-photo': [index: number, direction: -1 | 1]
  'remove-photo': [index: number]
  'import-official': []
}>()

const { locale, t } = useLocale()
const officialDetails = ref<HTMLDetailsElement | null>(null)
const rulebookFileInput = ref<HTMLInputElement | null>(null)
const officialSourceInput = ref<HTMLInputElement | null>(null)

function openOfficialDetails() {
  if (officialDetails.value) officialDetails.value.open = true
  if (typeof officialDetails.value?.scrollIntoView === 'function') {
    officialDetails.value.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

function clearSelectedFileInput() {
  if (rulebookFileInput.value) rulebookFileInput.value.value = ''
}

function focusOfficialSource() {
  openOfficialDetails()
  officialSourceInput.value?.focus()
}

function openLocalFilePicker() {
  rulebookFileInput.value?.click()
}

defineExpose({ clearSelectedFileInput, focusOfficialSource, openLocalFilePicker, openOfficialDetails })
</script>

<template>
  <form class="tabletop-panel player-board mt-8 p-5 text-left sm:p-7" @submit.prevent="emit('submit')">
    <div v-if="intakeDraftAreas.length" data-testid="rulebook-intake-unsaved" class="mb-5 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
      <strong>{{ intakeDraftCopy.status(intakeDraftAreas.join(locale === 'zh-CN' ? '、' : ', ')) }}</strong>
      <span class="mt-1 block text-xs leading-5">{{ intakeDraftCopy.memoryOnly }}</span>
    </div>
    <p class="text-sm font-semibold text-ink/65">{{ t('documents.capture.label') }}</p>
    <div class="mt-3 grid gap-3 sm:grid-cols-3">
      <label for="rulebook-file" class="group flex min-h-32 flex-col rounded-xl border border-dashed border-ink/25 bg-canvas p-4 transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-copper/60 hover:bg-copper/5'">
        <svg class="h-6 w-6 text-copper" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M14.5 2.75H6.75a2 2 0 0 0-2 2v14.5a2 2 0 0 0 2 2h10.5a2 2 0 0 0 2-2V8.75z" /><path d="M14 2.75v6h5.25M8 13h8M8 16.5h6" /></svg>
        <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.pdf.title') }}</span>
        <span class="mt-1 text-sm leading-5 text-ink/45">{{ file?.name ?? t('documents.capture.pdf.detail') }}</span>
      </label>
      <label for="rulebook-camera" class="flex min-h-32 flex-col rounded-xl border border-ink/12 bg-paper p-4 text-ink transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-copper/60 hover:bg-copper/[0.1]'">
        <svg class="h-6 w-6 text-copper" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M4.75 7.75h3l1.25-2h6l1.25 2h3a1.75 1.75 0 0 1 1.75 1.75v8.75A1.75 1.75 0 0 1 19.25 20H4.75A1.75 1.75 0 0 1 3 18.25V9.5a1.75 1.75 0 0 1 1.75-1.75Z" /><circle cx="12" cy="13.5" r="3.25" /></svg>
        <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.camera.title') }}</span>
        <span class="mt-1 text-sm leading-5 text-ink/45">{{ t('documents.capture.camera.detail') }}</span>
      </label>
      <label for="rulebook-gallery" class="flex min-h-32 flex-col rounded-xl border border-ink/12 bg-paper p-4 text-ink transition" :class="intakeControlsDisabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer hover:border-indigo/50 hover:bg-indigo/[0.1]'">
        <svg class="h-6 w-6 text-indigo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="3.5" y="4" width="17" height="16" rx="2" /><circle cx="8.5" cy="9" r="1.25" /><path d="m5.5 17 4.3-4.3 3.1 3.1 2.1-2.1L18.5 17" /></svg>
        <span class="mt-auto font-display text-lg font-semibold">{{ t('documents.capture.gallery.title') }}</span>
        <span class="mt-1 text-sm leading-5 text-ink/45">{{ t('documents.capture.gallery.detail') }}</span>
      </label>
    </div>
    <input id="rulebook-file" ref="rulebookFileInput" :disabled="intakeControlsDisabled" accept="application/pdf,.pdf" type="file" class="sr-only" @change="emit('select-file', $event)">
    <input id="rulebook-camera" :disabled="intakeControlsDisabled" accept="image/*" capture="environment" type="file" class="sr-only" :aria-label="t('documents.capture.cameraAlt')" @change="emit('add-photos', $event)">
    <input id="rulebook-gallery" :disabled="intakeControlsDisabled" accept="image/*" multiple type="file" class="sr-only" :aria-label="t('documents.capture.galleryAlt')" @change="emit('add-photos', $event)">

    <div v-if="photographedPages.length" class="mt-4 rounded-xl border border-ink/10 bg-canvas p-3 sm:p-4">
      <div class="flex flex-wrap items-baseline justify-between gap-2">
        <p class="font-semibold">{{ t('documents.capture.photoCount', { count: photographedPages.length }) }}</p>
        <p class="text-xs leading-5 text-ink/45">{{ t('documents.capture.photoHint') }}</p>
      </div>
      <ol class="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <li v-for="(page, index) in photographedPages" :key="page.id" class="overflow-hidden rounded-lg border border-ink/10 bg-paper">
          <img :src="page.previewUrl" :alt="t('documents.capture.photoPage', { position: index + 1 })" class="aspect-[3/4] w-full object-cover">
          <div class="flex items-center justify-between gap-1 px-2 py-2">
            <span class="text-xs font-semibold text-ink/60">{{ t('documents.capture.photoPage', { position: index + 1 }) }}</span>
            <span class="flex gap-1">
              <button type="button" :disabled="intakeControlsDisabled || index === 0" class="rounded px-1.5 py-0.5 text-sm text-ink/55 hover:bg-canvas disabled:opacity-25" :aria-label="t('documents.capture.moveEarlier', { position: index + 1 })" @click="emit('move-photo', index, -1)">←</button>
              <button type="button" :disabled="intakeControlsDisabled || index === photographedPages.length - 1" class="rounded px-1.5 py-0.5 text-sm text-ink/55 hover:bg-canvas disabled:opacity-25" :aria-label="t('documents.capture.moveLater', { position: index + 1 })" @click="emit('move-photo', index, 1)">→</button>
              <button type="button" :disabled="intakeControlsDisabled" class="rounded px-1.5 py-0.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-25" :aria-label="t('documents.capture.remove', { position: index + 1 })" @click="emit('remove-photo', index)">×</button>
            </span>
          </div>
        </li>
      </ol>
    </div>
    <p v-else-if="file" class="mt-3 text-sm text-ink/45">{{ t('documents.file.change') }} · {{ t('documents.file.limit') }}</p>
    <p v-if="preparingPhotos" class="mt-4 rounded-lg bg-copper/8 px-4 py-3 text-sm text-copper" role="status">{{ t('documents.capture.preparing') }}</p>

    <label class="mt-4 block text-sm font-semibold">{{ t('documents.title.label') }} <span class="font-normal text-ink/40">{{ t('documents.optional') }}</span>
      <input v-model="title" :disabled="intakeControlsDisabled" maxlength="160" :placeholder="t('documents.title.placeholder')" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper disabled:opacity-50">
      <span v-if="photographedPages.length" class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.title.photoHint') }}</span>
    </label>

    <details ref="officialDetails" class="mt-4 border-t border-ink/10 pt-4">
      <summary class="cursor-pointer text-sm font-semibold text-ink/55">{{ t('documents.advanced') }}</summary>
      <div class="mt-4 stack-y-lg">
        <label class="block text-sm font-semibold">{{ t('documents.source.label') }}
          <input ref="officialSourceInput" v-model="officialSourceUrl" :disabled="intakeControlsDisabled" type="url" inputmode="url" maxlength="2000" placeholder="https://publisher.example.com/rulebook.pdf" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper disabled:opacity-50">
          <span class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.source.hint') }}</span>
        </label>
        <div class="rounded-lg border border-indigo/15 bg-indigo/[0.035] p-4">
          <p class="text-sm font-semibold">{{ t('documents.officialImport.title') }}</p>
          <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('documents.officialImport.detail') }}</p>
          <RulebookIdentityConfirmation
            v-if="identityTarget && officialSourceUrl.trim()"
            v-model="officialImportIdentityConfirmed"
            class="mt-3"
            :target="identityTarget"
            :source-context="identitySourceContext"
            :source="identitySource"
            :disabled="intakeControlsDisabled"
          />
          <label class="mt-3 flex items-start gap-3 text-sm leading-6 text-ink/65">
            <input v-model="officialImportRightsConfirmed" :disabled="intakeControlsDisabled" type="checkbox" class="mt-1 h-5 w-5 shrink-0 accent-indigo">
            <span>{{ t('documents.officialImport.consent') }}</span>
          </label>
          <button type="button" :disabled="!canImportOfficial" class="mt-3 min-h-11 rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" @click="emit('import-official')">{{ importingOfficial ? t('documents.officialImport.importing') : t('documents.officialImport.action') }}</button>
        </div>

        <label v-if="editionOptions.length" class="block text-sm font-semibold">{{ t('documents.game.label') }}
          <select v-model="editionId" :disabled="intakeControlsDisabled" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal disabled:opacity-50">
            <option value="">{{ t('documents.game.none') }}</option>
            <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
          </select>
        </label>
        <p v-else class="text-sm leading-6 text-ink/55">{{ t('documents.game.missing') }} <RouterLink :to="{ name: 'catalog' }" class="font-semibold text-indigo underline">{{ t('documents.game.organize') }}</RouterLink>{{ t('documents.game.missingTail') }}</p>

        <div v-if="modelConfigurationAvailable && !visualVisionCapable" class="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
          <p><span class="font-semibold">{{ t('documents.visual.warningLead') }}</span>{{ t('documents.visual.warningBody') }}</p>
          <RouterLink :to="{ name: 'model-settings' }" class="mt-1 inline-block font-semibold text-indigo underline underline-offset-2">{{ t('documents.visual.settings') }}</RouterLink>
        </div>

        <label class="block text-sm font-semibold">{{ t('documents.learningGoal.label') }} <span class="font-normal text-ink/40">{{ t('documents.optional') }}</span>
          <textarea v-model="learningGoal" :disabled="intakeControlsDisabled" maxlength="500" rows="3" :placeholder="t('documents.learningGoal.placeholder')" class="mt-2 w-full resize-y rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal leading-6 outline-none focus:border-copper disabled:opacity-50" />
          <span class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.learningGoal.hint') }}</span>
        </label>

        <label class="block text-sm font-semibold">{{ t('documents.sourceType') }}
          <select v-model="sourceType" :disabled="intakeControlsDisabled" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5 disabled:opacity-50">
            <option value="BASE_RULEBOOK">{{ t('documents.type.base') }}</option>
            <option value="EXPANSION_RULEBOOK">{{ t('documents.type.expansion') }}</option>
            <option value="OFFICIAL_FAQ">{{ t('documents.type.faq') }}</option>
            <option value="OFFICIAL_ERRATA">{{ t('documents.type.errata') }}</option>
          </select>
        </label>
      </div>
    </details>

    <button :disabled="!canUpload" class="mt-5 w-full rounded-lg bg-copper px-5 py-3.5 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40">
      {{ preparingVersionId ? t('documents.submitPreparing') : uploading ? t('documents.submitUploading') : t('documents.submit') }}
    </button>
  </form>
</template>
