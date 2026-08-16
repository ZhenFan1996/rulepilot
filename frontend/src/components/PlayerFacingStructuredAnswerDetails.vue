<script setup lang="ts">
import { computed } from 'vue'

import type { PlayerFacingRuleAnswer } from '@/lib/playerAnswerContract'

const props = defineProps<{
  answer: PlayerFacingRuleAnswer
}>()

const hasDetails = computed(() => Boolean(
  props.answer.calculations?.length
  || props.answer.situationChecks?.length
  || props.answer.walkthroughSteps?.length
  || props.answer.decisionBranches?.length
  || props.answer.exceptionClauses?.length
  || props.answer.termDefinitions?.length
  || props.answer.workedExamples?.length
  || props.answer.priorityResolutions?.length
  || props.answer.timingResolutions?.length
  || props.answer.tieResolutions?.length
  || props.answer.scopeResolutions?.length
  || props.answer.conceptComparisons?.length
  || props.answer.ruleOptions?.length,
))

const basisLabels: Record<string, string> = {
  RULE_ORDER: '规则顺序',
  EXPLANATION_ORDER: '讲解顺序',
  EXPLICIT_RULE: '规则明确写出',
  RULEBOOK_EXAMPLE: '规则书示例',
  EVIDENCE_BOUND_ILLUSTRATION: '依据原文演示',
  EXPLICIT_OVERRIDE: '明确覆盖',
  IMPOSSIBILITY_PRIORITY: '无法同时执行时的优先规则',
  CONFLICT_ONLY_OVERRIDE: '仅冲突时覆盖',
  CURRENT_PLAYER_CHOOSES: '当前玩家选择',
  PRINTED_TOP_TO_BOTTOM: '按印刷顺序',
  NORMAL_TURN_ORDER: '按通常回合顺序',
  SINGLE_TIEBREAKER: '单项平局规则',
  ORDERED_TIEBREAKERS: '依次比较',
  RANK_REWARD_SHIFT: '名次奖励顺延',
  POSITIONAL_PRIORITY: '位置优先',
  PLAYER_COUNT: '人数条件',
  ROLE_PRESENCE: '角色条件',
  GAME_MODE: '游戏模式',
  VARIANT_SELECTION: '变体选择',
  PLAYER_COUNT_EXCEPTION: '人数例外',
  ACTION_WINDOW: '行动时机',
  RESOURCE_FUNCTION: '资源用途',
  STORAGE_STATUS: '存放状态',
  RULE_SCOPE: '规则范围',
  DEFINITION_BOUNDARY: '定义边界',
  SOURCE_SELECTION: '按规则来源选择',
  TIMING_CATALOG: '按时机选择',
  ALTERNATIVE_ACTION: '替代行动',
  EXCLUSIVE_CHOICE: '互斥选择',
}

const statusLabels: Record<string, string> = {
  CONFIRMED: '条件已确认',
  CONTRADICTED: '条件不成立',
  NOT_PROVIDED: '还需补充条件',
  MATCHES_SCOPE: '适用',
  OUTSIDE_SCOPE: '不适用',
  NEEDS_CONTEXT: '还需补充情境',
}

function basisLabel(value: string) {
  return basisLabels[value] ?? value
}

function statusLabel(value: string) {
  return statusLabels[value] ?? value
}
</script>

<template>
  <div v-if="hasDetails" class="mt-4 stack-y-md text-sm leading-6" data-testid="player-facing-structured-answer-details">
    <section v-if="answer.calculations?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">计算过程</h3>
      <ul class="mt-2 stack-y-xs font-mono text-xs text-indigo">
        <li v-for="item in answer.calculations" :key="`${item.expression}-${item.result}`">{{ item.expression }} = {{ item.result }}</li>
      </ul>
    </section>

    <section v-if="answer.situationChecks?.length" class="rounded-xl border border-ink/10 bg-paper p-3">
      <h3 class="font-semibold text-ink">适用条件</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.situationChecks" :key="`${item.requirement}-${item.status}`" class="rounded-lg bg-canvas p-3">
          <p class="font-semibold text-indigo">{{ statusLabel(item.status) }}</p>
          <p class="mt-1">{{ item.requirement }}</p>
          <p v-if="item.playerFact" class="mt-1 text-ink/60">当前事实：{{ item.playerFact }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.walkthroughSteps?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] p-3">
      <h3 class="font-semibold text-ink">按步骤执行</h3>
      <ol class="mt-2 stack-y-sm">
        <li v-for="(item, index) in answer.walkthroughSteps" :key="`${index}-${item.instruction}`" class="rounded-lg bg-canvas p-3">
          <p class="font-semibold text-ink">{{ index + 1 }}. {{ item.instruction }}</p>
          <p class="mt-1 text-ink/65">{{ item.explanation }}</p>
          <p class="mt-1 text-xs text-copper">{{ basisLabel(item.orderBasis) }}</p>
        </li>
      </ol>
    </section>

    <section v-if="answer.decisionBranches?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">条件分支</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.decisionBranches" :key="`${item.condition}-${item.outcome}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">如果：</span>{{ item.condition }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">那么：</span>{{ item.outcome }}</p>
          <p class="mt-1 text-xs text-indigo">{{ basisLabel(item.basis) }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.exceptionClauses?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] p-3">
      <h3 class="font-semibold text-ink">例外情况</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.exceptionClauses" :key="`${item.condition}-${item.effect}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">条件：</span>{{ item.condition }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">效果：</span>{{ item.effect }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.termDefinitions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">术语说明</h3>
      <dl class="mt-2 stack-y-sm">
        <div v-for="item in answer.termDefinitions" :key="item.term" class="rounded-lg bg-canvas p-3">
          <dt class="font-semibold text-indigo">{{ item.term }}</dt>
          <dd class="mt-1">{{ item.definition }}</dd>
          <dd class="mt-1 text-ink/60">边界：{{ item.boundary }}</dd>
        </div>
      </dl>
    </section>

    <section v-if="answer.workedExamples?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] p-3">
      <h3 class="font-semibold text-ink">演示例子</h3>
      <ol class="mt-2 stack-y-sm">
        <li v-for="(item, index) in answer.workedExamples" :key="`${index}-${item.setup}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">情境：</span>{{ item.setup }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">行动：</span>{{ item.action }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">结果：</span>{{ item.outcome }}</p>
          <p class="mt-1 text-xs text-copper">{{ basisLabel(item.basis) }}</p>
        </li>
      </ol>
    </section>

    <section v-if="answer.priorityResolutions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">规则冲突与优先级</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.priorityResolutions" :key="`${item.baseRule}-${item.competingRule}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">基础规则：</span>{{ item.baseRule }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">冲突规则：</span>{{ item.competingRule }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">结论：</span>{{ item.resolution }}</p>
          <p class="mt-1 text-xs text-indigo">{{ basisLabel(item.basis) }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.timingResolutions?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] p-3">
      <h3 class="font-semibold text-ink">结算时机</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.timingResolutions" :key="`${item.timingContext}-${item.resolutionOrder}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">发生情境：</span>{{ item.timingContext }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">结算顺序：</span>{{ item.resolutionOrder }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">顺序依据：</span>{{ item.orderSource }}</p>
          <p class="mt-1 text-xs text-copper">{{ basisLabel(item.basis) }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.tieResolutions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">平局处理</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.tieResolutions" :key="`${item.tieContext}-${item.finalOutcome}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-ink">平局情境：</span>{{ item.tieContext }}</p>
          <ol class="mt-1 list-decimal pl-5"><li v-for="step in item.resolutionSteps" :key="step">{{ step }}</li></ol>
          <p class="mt-1"><span class="font-semibold text-ink">最终结果：</span>{{ item.finalOutcome }}</p>
          <p class="mt-1 text-xs text-indigo">{{ basisLabel(item.basis) }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.scopeResolutions?.length" class="rounded-xl border border-emerald-200 bg-emerald-50/60 p-3">
      <h3 class="font-semibold text-ink">规则适用范围</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.scopeResolutions" :key="`${item.ruleContext}-${item.currentSituation}`" class="rounded-lg bg-canvas p-3">
          <p class="font-semibold text-emerald-700">{{ statusLabel(item.matchStatus) }} · {{ basisLabel(item.basis) }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">规则情境：</span>{{ item.ruleContext }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">适用条件：</span>{{ item.governingCondition }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">当前情境：</span>{{ item.currentSituation }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">效果：</span>{{ item.effect }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.conceptComparisons?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] p-3">
      <h3 class="font-semibold text-ink">概念对比</h3>
      <ul class="mt-2 stack-y-sm">
        <li v-for="item in answer.conceptComparisons" :key="`${item.leftConcept}-${item.rightConcept}`" class="rounded-lg bg-canvas p-3">
          <p><span class="font-semibold text-indigo">{{ item.leftConcept }}：</span>{{ item.leftDefinition }}</p>
          <p class="mt-1"><span class="font-semibold text-indigo">{{ item.rightConcept }}：</span>{{ item.rightDefinition }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">共同点：</span>{{ item.commonGround }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">关键区别：</span>{{ item.keyDifference }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">实际边界：</span>{{ item.practicalBoundary }}</p>
          <p class="mt-1 text-xs text-indigo">{{ basisLabel(item.basis) }}</p>
        </li>
      </ul>
    </section>

    <section v-if="answer.ruleOptions?.length" class="rounded-xl border border-copper/20 bg-copper/[0.05] p-3">
      <h3 class="font-semibold text-ink">可选行动</h3>
      <ol class="mt-2 stack-y-sm">
        <li v-for="(item, index) in answer.ruleOptions" :key="`${item.optionName}-${index}`" class="rounded-lg bg-canvas p-3">
          <p class="font-semibold text-ink">{{ index + 1 }}. {{ item.optionName }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">决策情境：</span>{{ item.decisionContext }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">选择规则：</span>{{ item.selectionRule }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">可用条件：</span>{{ item.availabilityCondition }}</p>
          <p class="mt-1"><span class="font-semibold text-ink">结果：</span>{{ item.result }}</p>
          <p class="mt-1 text-xs text-copper">{{ basisLabel(item.basis) }}</p>
        </li>
      </ol>
    </section>
  </div>
</template>
