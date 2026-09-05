<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'
import { playerFacingLanguageName } from '@/lib/playerFacingLanguage'
import type { RulebookDiscoveryIdentity, RulebookImportIdentitySource } from './types'

const props = defineProps<{
  target: RulebookDiscoveryIdentity
  sourceContext: RulebookDiscoveryIdentity | null
  source: RulebookImportIdentitySource | null
  disabled: boolean
}>()

const confirmed = defineModel<boolean>({ required: true })
const { locale } = useLocale()

const copy = computed(() => locale.value === 'zh-CN' ? {
  title: '确认规则书身份',
  detail: '目录选择、查找上下文和来源标注会分别保留。请先核对差异；RulePilot 不会把未知语言或不同版本自动合并。',
  target: '当前要绑定到', discovery: '查找时使用', source: '来源页面标注',
  edition: '版本', language: '语言', notKnown: '未知', notStated: '未标明', notVerified: '尚未核验', manual: '手动链接，没有查找身份',
  changed: '这份来源是为另一个目录版本找到的；当前选择已经变化。',
  sourceContextUnknown: '没有可核验的来源查找身份。',
  editionUnknown: '来源没有标明版本。', editionDiffers: '来源版本标注与当前目录版本不同。',
  catalogLanguageUnknown: '目录语言未知，不能用来源语言静默替换。',
  sourceLanguageUnknown: '来源语言尚未核验。', languageDiffers: '来源语言与当前目录语言不同。',
  confirm: '我已比较以上游戏、版本和语言，确认仍将这份规则书绑定到当前选择。',
} : {
  title: 'Confirm rulebook identity',
  detail: 'The catalog selection, discovery context, and source labels stay separate. Review differences before RulePilot binds the document; unknown languages and distinct editions are never merged automatically.',
  target: 'Bind to', discovery: 'Discovered for', source: 'Source page states',
  edition: 'Edition', language: 'Language', notKnown: 'Not known', notStated: 'Not stated', notVerified: 'not verified', manual: 'Manual URL; no discovery identity',
  changed: 'This source was discovered for another catalog edition; the selected edition changed.',
  sourceContextUnknown: 'The source discovery identity is not available.',
  editionUnknown: 'The source edition is not stated.', editionDiffers: 'The source edition label differs from the selected catalog edition.',
  catalogLanguageUnknown: 'The catalog language is not known and will not be silently replaced by the source language.',
  sourceLanguageUnknown: 'The source language is not verified.', languageDiffers: 'The source language differs from the selected catalog language.',
  confirm: 'I compared the game, edition, and language above and confirm that this rulebook should be bound to the current selection.',
})

function normalizedIdentity(value: string) {
  return value.normalize('NFKC').trim().replace(/\s+/g, ' ').toLocaleLowerCase()
}

function normalizedLanguage(value: string) {
  return value.trim().replaceAll('_', '-').toLocaleLowerCase()
}

function languageLabel(value: string, unknown: string) {
  if (!value || normalizedLanguage(value) === 'und') return unknown
  return playerFacingLanguageName(value, locale.value)
}

const issues = computed(() => {
  const found: string[] = []
  if (!props.sourceContext) found.push(copy.value.sourceContextUnknown)
  else if (props.sourceContext.editionId !== props.target.editionId) found.push(copy.value.changed)

  if (!props.source?.edition.trim()) found.push(copy.value.editionUnknown)
  else if (normalizedIdentity(props.source.edition) !== normalizedIdentity(props.target.editionName)) {
    found.push(copy.value.editionDiffers)
  }

  const targetLanguage = normalizedLanguage(props.target.language)
  const sourceLanguage = normalizedLanguage(props.source?.language ?? '')
  if (!targetLanguage || targetLanguage === 'und') found.push(copy.value.catalogLanguageUnknown)
  if (!props.source?.languageVerified || !sourceLanguage || sourceLanguage === 'und') {
    found.push(copy.value.sourceLanguageUnknown)
  } else if (targetLanguage !== 'und' && targetLanguage && targetLanguage !== sourceLanguage) {
    found.push(copy.value.languageDiffers)
  }
  return found
})
</script>

<template>
  <section class="rounded-lg border border-amber-200 bg-amber-50/70 p-4" aria-labelledby="rulebook-identity-title">
    <h4 id="rulebook-identity-title" class="text-sm font-semibold text-amber-950">{{ copy.title }}</h4>
    <p class="mt-1 text-xs leading-5 text-amber-950/75">{{ copy.detail }}</p>
    <dl class="mt-3 grid gap-2 text-xs sm:grid-cols-3">
      <div data-testid="identity-target" class="rounded-md bg-paper px-3 py-2">
        <dt class="font-semibold text-muted">{{ copy.target }}</dt>
        <dd class="mt-1 font-semibold text-ink">{{ target.gameName }}</dd>
        <dd class="mt-1 text-muted">{{ copy.edition }}: {{ target.editionName }} · {{ copy.language }}: {{ languageLabel(target.language, copy.notKnown) }}</dd>
      </div>
      <div data-testid="identity-discovery" class="rounded-md bg-paper px-3 py-2">
        <dt class="font-semibold text-muted">{{ copy.discovery }}</dt>
        <template v-if="sourceContext">
          <dd class="mt-1 font-semibold text-ink">{{ sourceContext.gameName }}</dd>
          <dd class="mt-1 text-muted">{{ copy.edition }}: {{ sourceContext.editionName }} · {{ copy.language }}: {{ languageLabel(sourceContext.language, copy.notKnown) }}</dd>
        </template>
        <dd v-else class="mt-1 text-muted">{{ copy.manual }}</dd>
      </div>
      <div data-testid="identity-source" class="rounded-md bg-paper px-3 py-2">
        <dt class="font-semibold text-muted">{{ copy.source }}</dt>
        <dd class="mt-1 text-muted">{{ copy.edition }}: {{ source?.edition || copy.notStated }}</dd>
        <dd class="mt-1 text-muted">{{ copy.language }}: {{ source?.language ? languageLabel(source.language, copy.notStated) : copy.notStated }}<template v-if="source?.language && !source.languageVerified"> ({{ copy.notVerified }})</template></dd>
      </div>
    </dl>
    <ul v-if="issues.length" class="mt-3 list-disc space-y-1 pl-5 text-xs leading-5 text-amber-950" role="alert">
      <li v-for="issue in issues" :key="issue">{{ issue }}</li>
    </ul>
    <label class="mt-3 flex items-start gap-3 text-sm leading-6 text-amber-950">
      <input v-model="confirmed" :disabled="disabled" type="checkbox" class="mt-1 size-5 shrink-0 accent-indigo">
      <span>{{ copy.confirm }}</span>
    </label>
  </section>
</template>
