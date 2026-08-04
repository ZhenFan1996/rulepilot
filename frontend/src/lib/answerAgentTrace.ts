export interface AnswerAgentActivity {
  sequence: number
  type: 'TOOL' | 'MODEL' | 'CRITIC' | 'VALIDATION'
  operation: string
  outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  latencyMs: number
  occurredAt: string
}

export interface AnswerAgentTraceItem {
  sequence: number
  kind: 'decision' | 'tool' | 'verification'
  label: string
  status: 'running' | 'done' | 'stopped'
}

/** Maps audited execution to player-safe decisions without exposing prompts, excerpts, or hidden reasoning. */
export function answerAgentTrace(
  activities: AnswerAgentActivity[],
  locale: 'zh-CN' | 'en' = 'zh-CN',
): AnswerAgentTraceItem[] {
  return activities
    .map(activity => traceItem(activity, locale))
    .filter((item): item is AnswerAgentTraceItem => item !== null)
    .filter((item, index, values) => index === 0
      || item.label !== values[index - 1]?.label
      || item.status !== values[index - 1]?.status)
    .slice(-8)
}

function traceItem(activity: AnswerAgentActivity, locale: 'zh-CN' | 'en'): AnswerAgentTraceItem | null {
  const status = activity.outcome === 'RUNNING'
    ? 'running'
    : activity.outcome === 'SUCCEEDED'
      ? 'done'
      : 'stopped'
  const operation = activity.operation
  if (operation.startsWith('nativeTool|search_rule_evidence') || operation === 'hybridRuleSearch') {
    return item(activity.sequence, 'tool', text(locale, '选择“检索规则依据”', 'Selected “Search rule evidence”'), status)
  }
  if (operation.startsWith('nativeTool|search_rule_relationships')) {
    return item(activity.sequence, 'tool', text(locale, '选择“查找例外与覆盖条款”', 'Selected “Find exceptions and overrides”'), status)
  }
  if (operation.startsWith('nativeTool|expand_rule_evidence_context')) {
    return item(activity.sequence, 'tool', text(locale, '选择“展开引用前后文”', 'Selected “Expand citation context”'), status)
  }
  if (operation.startsWith('nativeTool|read_rule_pages')) {
    return item(activity.sequence, 'tool', text(locale, '选择“读取原文页”', 'Selected “Read exact rulebook pages”'), status)
  }
  if (operation.startsWith('nativeTool|read_visual_page_facts')) {
    return item(activity.sequence, 'tool', text(locale, '选择“查看页面图示信息”', 'Selected “Inspect page visuals”'), status)
  }
  if (operation === 'calculateRuleMath') {
    return item(activity.sequence, 'tool', text(locale, '选择“核算规则结果”', 'Selected “Calculate rule result”'), status)
  }
  if (operation === 'checkRuleSituation') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对当前局面条件”', 'Selected “Check current situation conditions”'), status)
  }
  if (operation === 'buildRuleWalkthrough') {
    return item(activity.sequence, 'tool', text(locale, '选择“整理引用分步讲解”', 'Selected “Build cited walkthrough”'), status)
  }
  if (operation === 'traceRuleDependencies') {
    return item(activity.sequence, 'tool', text(locale, '选择“梳理规则前后关系”', 'Selected “Trace rule dependencies”'), status)
  }
  if (operation === 'buildRuleDecisionTable') {
    return item(activity.sequence, 'tool', text(locale, '选择“整理条件分支”', 'Selected “Build rule decision table”'), status)
  }
  if (operation === 'buildRuleExceptionList') {
    return item(activity.sequence, 'tool', text(locale, '选择“逐条核对例外和限制”', 'Selected “Validate exceptions and restrictions”'), status)
  }
  if (operation === 'defineRuleTerms') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对规则书术语定义”', 'Selected “Validate rulebook term definitions”'), status)
  }
  if (operation === 'illustrateRule') {
    return item(activity.sequence, 'tool', text(locale, '选择“整理带出处的规则示例”', 'Selected “Build a cited rule example”'), status)
  }
  if (operation === 'resolveRulePriority') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对规则优先关系”', 'Selected “Resolve rule priority”'), status)
  }
  if (operation === 'checkRuleConflicts') {
    return item(activity.sequence, 'tool', text(locale, '选择“检查两条规则是否真冲突”', 'Selected “Check whether rules truly conflict”'), status)
  }
  if (operation === 'showRuleEvidence') {
    return item(activity.sequence, 'tool', text(locale, '选择“展示最直接的原文依据”', 'Selected “Show the most direct source rule”'), status)
  }
  if (operation === 'checkRulePermission') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对规则是否允许”', 'Selected “Check whether the rule permits this”'), status)
  }
  if (operation === 'resolveRuleTiming') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对同时触发顺序”', 'Selected “Resolve simultaneous-effect order”'), status)
  }
  if (operation === 'resolveRuleTie') {
    return item(activity.sequence, 'tool', text(locale, '选择“逐级核对平局判定”', 'Selected “Resolve the tie-break ladder”'), status)
  }
  if (operation === 'resolveRuleScope') {
    return item(activity.sequence, 'tool', text(locale, '选择“核对规则适用范围”', 'Selected “Check rule applicability”'), status)
  }
  if (operation === 'compareRuleConcepts') {
    return item(activity.sequence, 'tool', text(locale, '选择“对比两个规则概念”', 'Selected “Compare rule concepts”'), status)
  }
  if (operation === 'listRuleOptions') {
    return item(activity.sequence, 'tool', text(locale, '选择“列出完整规则选项”', 'Selected “List complete rule options”'), status)
  }
  if (operation.startsWith('nativeModelTurn')) {
    return item(activity.sequence, 'decision', text(locale, '根据当前证据判断下一步', 'Decided the next step from current evidence'), status)
  }
  if (operation === 'nativeToolFallback') {
    return item(activity.sequence, 'verification', text(locale, '工具检索未补到新证据，保留已验证结果', 'Tool search added no evidence; kept the verified result'), status)
  }
  if (operation === 'nativeCompletionRequirement') {
    return item(activity.sequence, 'verification', text(locale, '证据工具尚未调用，继续查找依据', 'Evidence tool had not run; continued searching'), status)
  }
  if (operation === 'repairPublicationValidation') {
    return item(activity.sequence, 'verification', text(locale, '修正引用后重新核对回答', 'Corrected citations and checked the answer again'), status)
  }
  if (operation === 'repairRuleCalculation') {
    return item(activity.sequence, 'verification', text(locale, '修正公式后重新核算结果', 'Corrected the formula and recalculated the result'), status)
  }
  if (operation === 'repairRuleSituationCheck') {
    return item(activity.sequence, 'verification', text(locale, '修正局面条件后重新核对', 'Corrected the situation checks and validated them again'), status)
  }
  if (operation === 'repairRuleWalkthrough') {
    return item(activity.sequence, 'verification', text(locale, '修正步骤顺序后重新核对', 'Corrected the walkthrough order and validated it again'), status)
  }
  if (operation === 'repairRuleDecisionTable') {
    return item(activity.sequence, 'verification', text(locale, '修正条件分支后重新核对', 'Corrected the decision branches and validated them again'), status)
  }
  if (operation === 'repairRuleExceptionList') {
    return item(activity.sequence, 'verification', text(locale, '修正例外条款后重新核对', 'Corrected the exception clauses and validated them again'), status)
  }
  if (operation === 'repairRuleTermDefinitions') {
    return item(activity.sequence, 'verification', text(locale, '修正术语定义后重新核对', 'Corrected the term definitions and validated them again'), status)
  }
  if (operation === 'repairRuleOptions') {
    return item(activity.sequence, 'verification', text(locale, '补全规则选项后重新核对', 'Completed the rule option list and validated it again'), status)
  }
  if (operation === 'repairRuleWorkedExamples') {
    return item(activity.sequence, 'verification', text(locale, '修正示例后重新核对规则出处', 'Corrected the example and validated its sources again'), status)
  }
  if (operation === 'repairRulePriorityResolutions') {
    return item(activity.sequence, 'verification', text(locale, '修正规则优先关系后重新核对', 'Corrected the rule priority relationship and validated it again'), status)
  }
  if (operation === 'repairRuleTimingResolutions') {
    return item(activity.sequence, 'verification', text(locale, '修正时序关系后重新核对', 'Corrected the timing relationship and validated it again'), status)
  }
  if (operation === 'repairRuleTieResolutions') {
    return item(activity.sequence, 'verification', text(locale, '补全平局步骤和最终结果后重新核对', 'Completed the tie steps and final outcome, then validated them again'), status)
  }
  if (operation === 'repairRuleScopeResolutions') {
    return item(activity.sequence, 'verification', text(locale, '修正适用条件与当前局面后重新核对', 'Corrected the applicability conditions and current situation, then validated them again'), status)
  }
  if (operation === 'repairRuleConceptComparisons') {
    return item(activity.sequence, 'verification', text(locale, '修正概念定义与使用边界后重新核对', 'Corrected the concept definitions and usage boundary, then validated them again'), status)
  }
  if (activity.type === 'CRITIC') {
    return item(activity.sequence, 'verification', text(locale, '复核回答是否被引用支持', 'Reviewed whether citations support the answer'), status)
  }
  if (activity.type === 'VALIDATION') {
    return item(activity.sequence, 'verification', text(locale, '核对工具结果与引用边界', 'Validated tool results and citation boundaries'), status)
  }
  if (activity.type === 'MODEL') {
    return item(activity.sequence, 'decision', text(locale, '根据已验证证据组织回答', 'Composed from validated evidence'), status)
  }
  return null
}

function item(
  sequence: number,
  kind: AnswerAgentTraceItem['kind'],
  label: string,
  status: AnswerAgentTraceItem['status'],
): AnswerAgentTraceItem {
  return { sequence, kind, label, status }
}

function text(locale: 'zh-CN' | 'en', zh: string, en: string) {
  return locale === 'en' ? en : zh
}
