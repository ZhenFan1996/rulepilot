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
  actor?: string
  stage?: string
  nextAction?: string
}

export function streamedAnswerTraceItem(
  activity: import('@/lib/structuredAnswerStream').AnswerStreamActivity,
  locale: 'zh-CN' | 'en' = 'zh-CN',
): AnswerAgentTraceItem {
  const status = activity.status === 'running' ? 'running'
    : activity.status === 'succeeded' ? 'done' : 'stopped'
  const actor = streamActor(activity.actor, locale)
  const label = activity.message || streamStage(activity.stage, locale)
  const kind = activity.actor === 'answer_reviewer' || activity.actor === 'answer_validator'
    ? 'verification'
    : activity.actor === 'answer_agent' ? 'decision' : 'tool'
  return { sequence: activity.sequence, kind, label, status, actor, stage: activity.stage, nextAction: activity.nextAction }
}

function streamActor(actor: import('@/lib/structuredAnswerStream').AnswerStreamActivity['actor'], locale: 'zh-CN' | 'en') {
  const labels = locale === 'en'
    ? { answer_agent: 'Answer agent', rulebook_search: 'Rulebook search', rulebook_reader: 'Rulebook reader', answer_reviewer: 'Evidence reviewer', answer_validator: 'Citation validator', rulebook_tool: 'Rulebook tool' }
    : { answer_agent: '答疑 Agent', rulebook_search: '规则检索', rulebook_reader: '规则书阅读器', answer_reviewer: '证据复核', answer_validator: '引用校验', rulebook_tool: '规则工具' }
  return labels[actor]
}

function streamStage(stage: import('@/lib/structuredAnswerStream').AnswerStreamActivity['stage'], locale: 'zh-CN' | 'en') {
  const labels = locale === 'en'
    ? { searching_evidence: 'Searching the indexed rulebook for direct evidence', checking_exceptions: 'Checking exceptions and override clauses', expanding_context: 'Reading the surrounding context of the citation', reading_pages: 'Reading the exact rulebook pages', composing_answer: 'Composing only from verified evidence', reviewing_support: 'Reviewing whether each claim is supported', validating_citations: 'Validating citation ownership and page boundaries', correcting_answer: 'Correcting an answer that did not pass validation', evidence_search_stalled: 'Supplementary evidence search made no new progress; the answer continues with checked evidence', checking_rule_details: 'Checking a rule-specific detail' }
    : { searching_evidence: '正在索引规则书中查找直接依据', checking_exceptions: '正在核对例外与覆盖条款', expanding_context: '正在阅读引用前后的完整语境', reading_pages: '正在读取对应的规则书原页', composing_answer: '正在只根据已核实证据组织回答', reviewing_support: '正在复核每个结论是否有依据', validating_citations: '正在校验引用归属与页码边界', correcting_answer: '回答未通过校验，正在修正', evidence_search_stalled: '补充证据查找没有新增进展，已停止这一步', checking_rule_details: '正在核对具体规则细节' }
  return labels[stage]
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
}

function traceItem(activity: AnswerAgentActivity, locale: 'zh-CN' | 'en'): AnswerAgentTraceItem | null {
  const operation = activity.operation
  const status = activity.outcome === 'RUNNING'
    ? 'running'
    : activity.outcome === 'REJECTED' && isRecoverableCorrection(operation)
      ? 'running'
    : activity.outcome === 'SUCCEEDED'
      ? 'done'
      : 'stopped'
  if (operation.startsWith('nativeTool|search_rule_evidence') || operation === 'hybridRuleSearch') {
    return item(activity.sequence, 'tool', text(locale, '查找规则依据', 'Searching rule evidence'), status)
  }
  if (operation.startsWith('nativeTool|search_rule_relationships')) {
    return item(activity.sequence, 'tool', text(locale, '查找例外与覆盖条款', 'Finding exceptions and overrides'), status)
  }
  if (operation.startsWith('nativeTool|expand_rule_evidence_context')) {
    return item(activity.sequence, 'tool', text(locale, '展开引用前后文', 'Expanding citation context'), status)
  }
  if (operation.startsWith('nativeTool|read_rule_pages')) {
    return item(activity.sequence, 'tool', text(locale, '读取原文页', 'Reading exact rulebook pages'), status)
  }
  if (operation.startsWith('nativeTool|read_visual_page_facts')) {
    return item(activity.sequence, 'tool', text(locale, '查看页面图示信息', 'Checking page visuals'), status)
  }
  if (operation === 'calculateRuleMath') {
    return item(activity.sequence, 'tool', text(locale, '核算规则结果', 'Calculating the rule result'), status)
  }
  if (operation === 'checkRuleSituation') {
    return item(activity.sequence, 'tool', text(locale, '核对当前局面条件', 'Checking current situation conditions'), status)
  }
  if (operation === 'buildRuleWalkthrough') {
    return item(activity.sequence, 'tool', text(locale, '整理引用分步讲解', 'Building a cited walkthrough'), status)
  }
  if (operation === 'traceRuleDependencies') {
    return item(activity.sequence, 'tool', text(locale, '梳理规则前后关系', 'Tracing rule dependencies'), status)
  }
  if (operation === 'buildRuleDecisionTable') {
    return item(activity.sequence, 'tool', text(locale, '整理条件分支', 'Organizing rule branches'), status)
  }
  if (operation === 'buildRuleExceptionList') {
    return item(activity.sequence, 'tool', text(locale, '逐条核对例外和限制', 'Checking exceptions and restrictions'), status)
  }
  if (operation === 'defineRuleTerms') {
    return item(activity.sequence, 'tool', text(locale, '核对规则书术语定义', 'Checking rulebook term definitions'), status)
  }
  if (operation === 'illustrateRule') {
    return item(activity.sequence, 'tool', text(locale, '整理带出处的规则示例', 'Building a cited rule example'), status)
  }
  if (operation === 'resolveRulePriority') {
    return item(activity.sequence, 'tool', text(locale, '核对规则优先关系', 'Checking rule priority'), status)
  }
  if (operation === 'checkRuleConflicts') {
    return item(activity.sequence, 'tool', text(locale, '检查两条规则是否真冲突', 'Checking whether the rules conflict'), status)
  }
  if (operation === 'showRuleEvidence') {
    return item(activity.sequence, 'tool', text(locale, '展示最直接的原文依据', 'Showing the most direct source rule'), status)
  }
  if (operation === 'checkRulePermission') {
    return item(activity.sequence, 'tool', text(locale, '核对规则是否允许', 'Checking whether the rule permits this'), status)
  }
  if (operation === 'resolveRuleTiming') {
    return item(activity.sequence, 'tool', text(locale, '核对同时触发顺序', 'Checking simultaneous-effect order'), status)
  }
  if (operation === 'resolveRuleTie') {
    return item(activity.sequence, 'tool', text(locale, '逐级核对平局判定', 'Checking the tie-break ladder'), status)
  }
  if (operation === 'resolveRuleScope') {
    return item(activity.sequence, 'tool', text(locale, '核对规则适用范围', 'Checking rule applicability'), status)
  }
  if (operation === 'compareRuleConcepts') {
    return item(activity.sequence, 'tool', text(locale, '对比两个规则概念', 'Comparing rule concepts'), status)
  }
  if (operation === 'listRuleOptions') {
    return item(activity.sequence, 'tool', text(locale, '列出完整规则选项', 'Listing complete rule options'), status)
  }
  if (operation.startsWith('nativeModelTurn')) {
    return item(activity.sequence, 'decision', text(locale, '根据当前证据判断下一步', 'Decided the next step from current evidence'), status)
  }
  if (operation.startsWith('nativeToolFallback')) {
    return item(activity.sequence, 'verification', nativeFallbackLabel(operation, locale), 'stopped')
  }
  if (operation.startsWith('nativeObservationNoProgress')) {
    return item(activity.sequence, 'verification', text(locale, '补充证据查找没有新增进展，已停止这一步；继续使用已有核验证据', 'Supplementary evidence search made no new progress, so that step stopped; continuing with checked evidence'), 'stopped')
  }
  if (operation === 'nativeCompletionRequirement') {
    return item(activity.sequence, 'verification', text(locale, '还需要规则依据，继续查找', 'More rule evidence is needed; continuing the search'), status)
  }
  if (activity.outcome === 'REJECTED' && isRecoverableCorrection(operation)) {
    return item(activity.sequence, 'verification', text(locale, '返回内容还需要修正，正在继续核对', 'The returned content needs correction; checking again'), status)
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
    return item(activity.sequence, 'verification', text(locale, '核对查找结果与引用边界', 'Checked lookup results and citation boundaries'), status)
  }
  if (activity.type === 'MODEL') {
    return item(activity.sequence, 'decision', text(locale, '根据已验证证据组织回答', 'Composed from validated evidence'), status)
  }
  return null
}

function nativeFallbackLabel(operation: string, locale: 'zh-CN' | 'en') {
  const reason = operation.split('|', 2)[1]
  if (reason === 'TIMEOUT') {
    return text(locale, '补充证据查找超时，已停止这一步；继续使用已有核验证据', 'Supplementary evidence search timed out, so that step stopped; continuing with checked evidence')
  }
  if (reason === 'MODEL_CAPABILITY_UNAVAILABLE' || reason === 'TOOL_ALLOWLIST_UNAVAILABLE') {
    return text(locale, '补充证据能力当前不可用，已停止这一步；继续使用已有核验证据', 'Supplementary evidence lookup is unavailable, so that step stopped; continuing with checked evidence')
  }
  if (reason === 'CANCELLED') {
    return text(locale, '补充证据查找已取消；继续使用已有核验证据', 'Supplementary evidence search was cancelled; continuing with checked evidence')
  }
  if (reason?.endsWith('_BUDGET') || reason?.startsWith('OBSERVATION_BUDGET_')) {
    return text(locale, '补充证据查找已达到本次容量边界，已停止这一步；继续使用已有核验证据', 'Supplementary evidence search reached this run\'s capacity, so that step stopped; continuing with checked evidence')
  }
  if (reason?.endsWith('_NO_PROGRESS')) {
    return text(locale, '补充证据查找没有新增进展，已停止这一步；继续使用已有核验证据', 'Supplementary evidence search made no new progress, so that step stopped; continuing with checked evidence')
  }
  return text(locale, '补充证据查找未能继续，已停止这一步；继续使用已有核验证据', 'Supplementary evidence search could not continue, so that step stopped; continuing with checked evidence')
}

function isRecoverableCorrection(operation: string) {
  return operation === 'nativeCompletionRequirement'
    || operation === 'nativeEmptyCompletion'
    || operation === 'nativeCompletionProtocol'
    || operation === 'nativeActionProtocol'
    || operation === 'nativeToolSchema'
    || operation.startsWith('nativeObs|')
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
