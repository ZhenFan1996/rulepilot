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
