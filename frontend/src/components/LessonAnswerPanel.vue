<script setup lang="ts">
import { computed } from 'vue'

import VoiceQuestionCapture from '@/components/VoiceQuestionCapture.vue'
import type {
  AnswerTurn,
  ConfirmedRuling,
  LearningIntent,
  StructuredRuleAnswer,
} from '@/composables/useLessonAnswers'

interface LessonAnswerSection {
  position: number
  title: string
}

const props = defineProps<{
  currentSection: LessonAnswerSection
  question: string
  answer: StructuredRuleAnswer | null
  answeredQuestion: string
  answerTurns: AnswerTurn[]
  activeLearningIntent: LearningIntent | null
  answerLoading: boolean
  answerError: string
  online: boolean
  ruling: ConfirmedRuling | null
  rulingSaving: boolean
  rulingError: string
  rulingConflict: boolean
  editingRuling: boolean
  editedVerdict: string
  editedExplanation: string
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'update:editing-ruling': [value: boolean]
  'update:edited-verdict': [value: string]
  'update:edited-explanation': [value: string]
  ask: []
  requestHelp: [intent: LearningIntent]
  openCardOcr: []
  voiceTranscript: [text: string]
  confirmRuling: []
  reloadRuling: []
  saveRulingRevision: []
}>()

const questionModel = computed({
  get: () => props.question,
  set: (value: string) => emit('update:question', value),
})
const editedVerdictModel = computed({
  get: () => props.editedVerdict,
  set: (value: string) => emit('update:edited-verdict', value),
})
const editedExplanationModel = computed({
  get: () => props.editedExplanation,
  set: (value: string) => emit('update:edited-explanation', value),
})
const previousAnswerTurns = computed(() => props.answerTurns.slice(0, -1))
const currentAnswerTurn = computed(() => props.answerTurns.at(-1) ?? null)

function learningIntentLabel(intent: LearningIntent | null) {
  if (intent === null) return '规则答疑'
  return {
    SIMPLIFY: '换个简单说法',
    EXAMPLE: '走一个具体例子',
    WHY: '梳理前后关系',
    EXCEPTIONS: '查找例外和限制',
  }[intent]
}

function confidenceLabel(confidence: StructuredRuleAnswer['confidence']) {
  return { HIGH: '高置信度', MEDIUM: '中等置信度', LOW: '低置信度' }[confidence]
}

function citationPages(citation: StructuredRuleAnswer['citations'][number]) {
  return citation.pageFrom === citation.pageTo
    ? `第 ${citation.pageFrom} 页`
    : `第 ${citation.pageFrom}–${citation.pageTo} 页`
}

function answerFailureMessage(status: StructuredRuleAnswer['status']) {
  return {
    ANSWERED: '',
    CLARIFICATION_REQUIRED: '',
    INSUFFICIENT_EVIDENCE: '当前规则资料没有足够依据，系统没有生成推测性结论。',
    MODEL_TIMEOUT: '回答生成超时。你可以重新提交，已加载的讲解和原始规则证据不受影响。',
    INVALID_MODEL_OUTPUT: '生成结果未通过结构或引用校验，未经验证的内容没有显示。',
    VERSION_CONFLICT: '检索证据与当前规则版本不一致，请返回讲解并确认所选版本。',
  }[status]
}
</script>

<template>
  <section id="lesson-question-panel" class="mt-8 scroll-mt-6 border-t border-ink/10 pt-7" aria-labelledby="lesson-question-title">
    <div class="rounded-3xl border border-indigo/20 bg-indigo/[0.035] p-4 sm:p-6">
      <div class="mt-2 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p class="text-xs font-semibold uppercase tracking-[0.14em] text-indigo">规则答疑</p>
          <h3 id="lesson-question-title" class="mt-1 font-display text-2xl font-semibold">这一步哪里不清楚？</h3>
          <p class="mt-2 text-sm leading-6 text-ink/55">直接问；回答会重新查找“{{ currentSection.title }}”对应的规则原文和页码。</p>
        </div>
        <span class="rounded-full bg-indigo/8 px-3 py-1.5 text-xs font-semibold text-indigo">第 {{ currentSection.position }} 节上下文</span>
      </div>

      <ol v-if="previousAnswerTurns.length" class="mt-5 space-y-3" aria-label="本节之前的问答">
        <li v-for="(turn, index) in previousAnswerTurns" :key="`${index}-${turn.question}`" class="rounded-2xl border border-ink/8 bg-canvas p-4">
          <p class="text-xs font-semibold text-ink/45">{{ turn.learningIntent ? learningIntentLabel(turn.learningIntent) : '你问' }}</p>
          <p class="mt-1 text-sm leading-6">{{ turn.question }}</p>
          <p class="mt-3 border-l-2 border-copper pl-3 text-sm font-semibold leading-6">{{ turn.answer.shortVerdict }}</p>
        </li>
      </ol>

      <div class="mt-5 rounded-2xl bg-copper/[0.07] p-4">
        <p class="text-sm font-semibold">哪里还没弄明白？</p>
        <p class="mt-1 text-xs leading-5 text-ink/50">选择一种方式，规则助手会重新查这一节的依据再讲一次。</p>
        <div class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'SIMPLIFY')">讲简单点</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'EXAMPLE')">走个例子</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'WHY')">前后怎么接</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'EXCEPTIONS')">例外和限制</button>
        </div>
      </div>

      <form class="mt-5" @submit.prevent="emit('ask')">
        <div class="mb-3 flex flex-wrap items-start gap-3">
          <button
            type="button"
            class="min-h-11 rounded-xl border border-indigo/25 bg-indigo/5 px-4 text-sm font-semibold text-indigo transition hover:bg-indigo/10 disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="answerLoading || !online"
            @click="emit('openCardOcr')"
          >
            拍照识别卡牌文字
          </button>
          <VoiceQuestionCapture :disabled="answerLoading || !online" @transcript="emit('voiceTranscript', $event)" />
        </div>
        <label for="lesson-question" class="sr-only">针对当前讲解章节提问</label>
        <textarea
          id="lesson-question"
          v-model="questionModel"
          rows="3"
          maxlength="800"
          :disabled="answerLoading || !online"
          placeholder="例如：为什么完成目标后才计算这一分？"
          class="w-full resize-y rounded-2xl border border-ink/15 bg-canvas px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:cursor-not-allowed disabled:opacity-55"
        />
        <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
          <p class="text-xs text-ink/45">{{ question.length }}/800 · 回答必须附带当前版本中的规则依据</p>
          <button
            type="submit"
            :disabled="answerLoading || !online || !question.trim()"
            class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {{ answerLoading ? '正在查找规则依据…' : online ? '提交问题' : '离线时无法提问' }}
          </button>
        </div>
      </form>

      <p v-if="answerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ answerError }}</p>
      <div v-else-if="answerLoading" class="mt-5 space-y-3 rounded-2xl border border-ink/8 p-5" aria-live="polite">
        <div class="flex items-center gap-3">
          <span class="size-3 animate-pulse rounded-full bg-indigo" aria-hidden="true" />
          <p class="text-sm font-semibold">正在{{ activeLearningIntent ? learningIntentLabel(activeLearningIntent) : '理解这次追问' }}并重新核对规则书…</p>
        </div>
        <ol class="grid gap-2 text-xs leading-5 text-ink/55 sm:grid-cols-3">
          <li><span class="font-semibold text-indigo">1.</span> 对齐问题与本节术语</li>
          <li><span class="font-semibold text-indigo">2.</span> 查找规则书原文</li>
          <li><span class="font-semibold text-indigo">3.</span> 核对引用后组织答案</li>
        </ol>
        <p class="text-xs leading-5 text-ink/50">如果规则书是外语，会先补充检索短语；这不是直接使用模型记忆作答。</p>
        <div class="h-4 w-4/5 animate-pulse rounded bg-ink/10" />
        <div class="h-4 w-3/5 animate-pulse rounded bg-ink/10" />
      </div>

      <article v-else-if="answer" class="mt-5 overflow-hidden rounded-3xl border border-ink/10 bg-canvas" aria-live="polite">
        <div class="p-5 sm:p-6">
          <p class="text-xs font-semibold text-ink/45">{{ currentAnswerTurn?.learningIntent ? learningIntentLabel(currentAnswerTurn.learningIntent) : '你问' }}：{{ answeredQuestion }}</p>
          <div class="flex flex-wrap items-center gap-2 text-xs font-semibold">
            <span :class="answer.confidence === 'LOW' ? 'bg-red-50 text-red-700' : 'bg-emerald-50 text-emerald-700'" class="rounded-full px-3 py-1.5">{{ confidenceLabel(answer.confidence) }}</span>
            <span class="rounded-full bg-ink/6 px-3 py-1.5 text-ink/60">{{ answer.confirmedRulingId ? '已确认裁定' : answer.official ? '官方来源' : '上传规则资料' }}</span>
          </div>
          <p class="mt-4 font-display text-xl font-semibold leading-8">{{ answer.shortVerdict }}</p>

          <p v-if="answer.status === 'CLARIFICATION_REQUIRED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answer.clarification }}</p>
          <p v-else-if="answer.status !== 'ANSWERED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answerFailureMessage(answer.status) }}</p>

          <div v-if="answer.status === 'ANSWERED'" class="mt-5 border-t border-ink/10 pt-4">
            <p class="text-sm font-semibold text-indigo">详细解释</p>
            <p class="mt-3 leading-7 text-ink/70">{{ answer.explanation }}</p>
            <ul v-if="answer.exceptions.length" class="mt-3 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65">
              <li v-for="exception in answer.exceptions" :key="exception">{{ exception }}</li>
            </ul>
          </div>

          <div v-if="answer.status === 'ANSWERED'" class="mt-5 flex flex-wrap gap-2 border-t border-ink/10 pt-4" aria-label="继续追问">
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'WHY')">前后怎么接</button>
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXAMPLE')">走个例子</button>
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXCEPTIONS')">例外和限制</button>
          </div>
        </div>

        <details v-if="answer.citations.length" class="border-t border-indigo/15 bg-indigo/5 p-5 sm:p-6">
          <summary class="cursor-pointer font-semibold text-indigo">规则出处与页码（{{ answer.citations.length }}）</summary>
          <ol class="mt-4 space-y-3">
            <li v-for="citation in answer.citations" :key="citation.chunkId" class="rounded-2xl border border-indigo/15 bg-paper p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="font-semibold">{{ citation.heading }}</p>
                <span class="text-xs font-semibold text-indigo">{{ citationPages(citation) }}</span>
              </div>
              <p class="mt-2 text-sm leading-6 text-ink/65">{{ citation.excerpt }}</p>
            </li>
          </ol>
        </details>

        <div v-if="answer.status === 'ANSWERED'" class="border-t border-ink/10 p-5 sm:p-6">
          <p v-if="rulingError" class="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ rulingError }}</p>
          <div v-if="rulingConflict" class="rounded-2xl border border-amber-300 bg-amber-50 p-4" role="alert">
            <p class="font-semibold text-amber-950">另一处编辑已经更新了这条裁定</p>
            <p class="mt-1 text-sm leading-6 text-amber-900">为避免覆盖他人的修改，请加载服务器版本后重新编辑。</p>
            <button class="mt-3 min-h-11 rounded-xl bg-amber-900 px-4 text-sm font-semibold text-white" :disabled="rulingSaving" @click="emit('reloadRuling')">加载最新版本</button>
          </div>

          <div v-else-if="ruling && editingRuling" class="space-y-4">
            <div>
              <label for="ruling-verdict" class="text-sm font-semibold">一句话裁定</label>
              <textarea id="ruling-verdict" v-model="editedVerdictModel" rows="2" maxlength="2000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
            </div>
            <div>
              <label for="ruling-explanation" class="text-sm font-semibold">详细解释</label>
              <textarea id="ruling-explanation" v-model="editedExplanationModel" rows="5" maxlength="20000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
            </div>
            <div class="flex flex-wrap gap-3">
              <button class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40" :disabled="rulingSaving || !editedVerdict.trim() || !editedExplanation.trim()" @click="emit('saveRulingRevision')">{{ rulingSaving ? '保存中…' : '保存修改' }}</button>
              <button class="min-h-11 rounded-xl border border-ink/15 px-5 text-sm font-semibold" :disabled="rulingSaving" @click="emit('update:editing-ruling', false)">取消</button>
            </div>
          </div>

          <div v-else-if="ruling" class="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-emerald-50 p-4">
            <div>
              <p class="font-semibold text-emerald-900">已保存为确认裁定</p>
              <p class="mt-1 text-xs text-emerald-800">版本 {{ ruling.version }} · 引用 {{ ruling.citations.length }} 条</p>
            </div>
            <button class="min-h-11 rounded-xl border border-emerald-700 px-4 text-sm font-semibold text-emerald-900" @click="emit('update:editing-ruling', true)">编辑裁定</button>
          </div>

          <button v-else class="min-h-11 w-full rounded-xl border border-indigo/30 px-5 text-sm font-semibold text-indigo transition hover:bg-indigo/5 disabled:opacity-40" :disabled="rulingSaving" @click="emit('confirmRuling')">{{ rulingSaving ? '正在保存…' : '保存为已确认裁定' }}</button>
        </div>
      </article>
    </div>
  </section>
</template>
