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
}

export function streamedAnswerTraceItem(
  activity: import('@/lib/structuredAnswerStream').AnswerStreamActivity,
  locale: 'zh-CN' | 'en' = 'zh-CN',
): AnswerAgentTraceItem {
  const status = activity.status === 'running' ? 'running'
    : activity.status === 'succeeded' ? 'done' : 'stopped'
  const kind = activity.stage === 'repairing_action'
    || activity.stage === 'repairing_terminal'
    || activity.stage === 'publication_boundary'
    || activity.stage === 'agent_stopped'
    ? 'verification'
    : activity.actor === 'answer_agent' ? 'decision' : 'tool'
  return {
    sequence: activity.sequence,
    kind,
    label: activity.message || streamStage(activity.stage, locale),
    status,
    actor: streamActor(activity.actor, locale),
    stage: activity.stage,
  }
}

function streamActor(actor: import('@/lib/structuredAnswerStream').AnswerStreamActivity['actor'], locale: 'zh-CN' | 'en') {
  const labels = locale === 'en'
    ? { answer_agent: 'Answer Agent', rulebook_tool: 'Rulebook tool' }
    : { answer_agent: '答疑 Agent', rulebook_tool: '规则工具' }
  return labels[actor]
}

function streamStage(stage: import('@/lib/structuredAnswerStream').AnswerStreamActivity['stage'], locale: 'zh-CN' | 'en') {
  const labels = locale === 'en'
    ? { model_decision: 'The Agent is deciding from current evidence', read_tool: 'Reading rulebook evidence', tool_observation: 'Using the rulebook result', repairing_action: 'The same Agent is regenerating the complete action payload', repairing_terminal: 'The same Agent is regenerating the complete answer payload', publication_boundary: 'Checking the publication boundary', agent_stopped: 'The Agent stopped with the reported code' }
    : { model_decision: 'Agent 正在根据当前证据决定下一步', read_tool: '正在读取规则书依据', tool_observation: '正在使用规则书读取结果', repairing_action: '同一 Agent 正在重新生成完整动作载荷', repairing_terminal: '同一 Agent 正在重新生成完整回答载荷', publication_boundary: '正在核对发布边界', agent_stopped: 'Agent 已按报告的代码停止' }
  return labels[stage]
}

/** Maps the current native Agent audit stream without exposing prompts, schemas, or tool arguments. */
export function answerAgentTrace(
  activities: AnswerAgentActivity[],
  locale: 'zh-CN' | 'en' = 'zh-CN',
): AnswerAgentTraceItem[] {
  return activities
    .map(activity => traceItem(activity, locale))
    .filter((entry): entry is AnswerAgentTraceItem => entry !== null)
    .filter((entry, index, entries) => index === 0
      || entry.label !== entries[index - 1]?.label
      || entry.status !== entries[index - 1]?.status)
}

function traceItem(activity: AnswerAgentActivity, locale: 'zh-CN' | 'en'): AnswerAgentTraceItem | null {
  const operation = activity.operation
  const correction = isInternalCorrection(operation)
  const status: AnswerAgentTraceItem['status'] = activity.outcome === 'RUNNING'
    || (activity.outcome === 'REJECTED' && correction)
    ? 'running'
    : activity.outcome === 'SUCCEEDED' ? 'done' : 'stopped'

  if (operation.startsWith('nativeTool|')) {
    return item(activity.sequence, 'tool', toolLabel(operation.split('|')[1] ?? '', locale), status)
  }
  if (operation.startsWith('nativeModelTurn')) {
    return item(activity.sequence, 'decision', text(locale, '根据当前证据决定下一步', 'Deciding the next step from current evidence'), status)
  }
  if (operation.startsWith('nativeObservationNoProgress')) {
    return item(activity.sequence, 'verification', text(locale, '没有获得新增证据，已停止这一步；已核验内容继续保留', 'No new evidence was found, so this step stopped; checked content remains'), 'stopped')
  }
  if (operation.startsWith('nativeToolFallback')) {
    const reason = operation.split('|')[1] || 'UNKNOWN'
    return item(activity.sequence, 'verification', text(locale, `工具步骤已停止（${reason}）；已核验内容继续保留`, `Tool step stopped (${reason}); checked content remains`), 'stopped')
  }
  if (operation === 'nativeCompletionRequirement') {
    return item(activity.sequence, 'verification', text(locale, '还缺少可核验依据，同一 Agent 正在继续', 'More verifiable evidence is required; the same Agent is continuing'), status)
  }
  if (correction) {
    return item(activity.sequence, 'verification', text(locale, '结构化结果未通过边界，同一 Agent 正在提交完整替代结果', 'The typed result did not pass the boundary; the same Agent is returning a complete replacement'), status)
  }
  if (activity.type === 'VALIDATION') {
    return item(activity.sequence, 'verification', text(locale, '校验证据身份与引用边界', 'Checking evidence identity and citation boundaries'), status)
  }
  if (activity.type === 'MODEL') {
    return item(activity.sequence, 'decision', text(locale, '根据已核验证据组织回答', 'Composing from checked evidence'), status)
  }
  if (activity.type === 'TOOL') {
    return item(activity.sequence, 'tool', text(locale, '读取规则书信息', 'Reading rulebook information'), status)
  }
  return null
}

function toolLabel(tool: string, locale: 'zh-CN' | 'en') {
  if (tool === 'search_rule_evidence') return text(locale, '查找规则依据', 'Searching rule evidence')
  if (tool === 'search_rule_relationships') return text(locale, '查找例外与覆盖条款', 'Finding exceptions and overrides')
  if (tool === 'expand_rule_evidence_context') return text(locale, '展开引用前后文', 'Expanding citation context')
  if (tool === 'read_rule_pages') return text(locale, '读取规则书原页', 'Reading exact rulebook pages')
  if (tool === 'read_visual_page_facts') return text(locale, '查看页面图示信息', 'Checking page visuals')
  if (tool === 'read_rule_page_image') return text(locale, '查看规则书页面', 'Reading a rulebook page image')
  if (tool === 'crop_rule_page_image') return text(locale, '查看页面局部', 'Reading a page region')
  return text(locale, '读取规则书信息', 'Reading rulebook information')
}

function isInternalCorrection(operation: string) {
  return operation === 'nativeCompletionRequirement'
    || operation === 'nativeEmptyCompletion'
    || operation === 'nativeCompletionProtocol'
    || operation === 'nativeActionProtocol'
    || operation === 'nativeToolSchema'
    || operation.startsWith('nativeObservationBudget|')
    || operation.startsWith('nativeObservationEnvelope|')
    || operation.startsWith('nativeObs|')
}

function item(sequence: number, kind: AnswerAgentTraceItem['kind'], label: string, status: AnswerAgentTraceItem['status']): AnswerAgentTraceItem {
  return { sequence, kind, label, status }
}

function text(locale: 'zh-CN' | 'en', zh: string, en: string) {
  return locale === 'en' ? en : zh
}
