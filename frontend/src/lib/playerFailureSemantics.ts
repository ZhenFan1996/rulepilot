import type { AppLocale } from './locale'

export type PlayerFailureCategory =
  | 'local-degradation'
  | 'retry-preserved'
  | 'repair-required'
  | 'internal-correction'

export interface PlayerFailureDescriptor {
  category: PlayerFailureCategory
  owner: string
  code: string | null
}

export function playerFailureCategoryCopy(category: PlayerFailureCategory, locale: AppLocale) {
  const english = locale === 'en'
  if (category === 'local-degradation') {
    return {
      title: english ? 'Local degradation' : '局部降级',
      detail: english
        ? 'Only the affected page, chapter, or visual is unavailable. Confirmed siblings and published text remain usable.'
        : '只影响对应页面、章节或配图；已确认的兄弟项和已发布正文继续保留。',
    }
  }
  if (category === 'retry-preserved') {
    return {
      title: english ? 'Retry unchanged; progress preserved' : '可原样重试，进度保留',
      detail: english
        ? 'The request itself was not rejected. Durable work remains, so a fresh attempt can reuse the same input.'
        : '请求本身没有被拒绝；已持久化进度仍在，可以用相同输入启动新任务。',
    }
  }
  if (category === 'repair-required') {
    return {
      title: english ? 'Repair before retrying' : '需修复后重试',
      detail: english
        ? 'Retrying unchanged is unsafe. Repair the reported input, authorization, source, ownership, version, persistence, identity, or citation boundary first.'
        : '原样重试不安全；请先修复已报告的输入、认证、来源、所有权、版本、保存、身份或引用边界。',
    }
  }
  return {
    title: english ? 'Internal JSON correction' : '内部 JSON 修正',
    detail: english
      ? 'This is not a player-input failure. The same Agent receives the complete candidate plus code, path, reason, schema, and allowed IDs and must return a complete replacement. Only exact repetition, no progress, or a resource stop ends this step.'
      : '这不是玩家输入失败；同一个 Agent 会收到完整候选以及 code、path、reason、schema、allowed IDs，并返回完整替代结果。只有完全重复、无进展或资源停止才会结束这一步。',
  }
}

export function answerFailureDescriptor(
  codeOrStatus: string,
  canRetryUnchanged: boolean | null,
  locale: AppLocale,
): PlayerFailureDescriptor {
  const normalized = codeOrStatus.trim().toUpperCase()
  const category = normalized.includes('INVALID_MODEL_OUTPUT')
      || normalized.includes('RESULT_INVALID')
      || normalized.includes('PROTOCOL')
      || normalized.includes('SCHEMA')
    ? 'internal-correction'
    : normalized.includes('CONTEXT')
        || normalized.includes('VERSION')
        || normalized.includes('PUBLIC_LESSON_UNAVAILABLE')
        || normalized.includes('AUTH')
        || normalized.includes('OWNERSHIP')
        || normalized.includes('CITATION')
      ? 'repair-required'
      : canRetryUnchanged === true
          || normalized.includes('TIMEOUT')
          || normalized.includes('UNAVAILABLE')
          || normalized.includes('RATE_LIMIT')
          || normalized.includes('TRANSPORT')
          || normalized.includes('REQUEST_FAILED')
          || normalized.includes('CANCELLED')
        ? 'retry-preserved'
        : 'repair-required'
  const owner = normalized.includes('RETRIEVAL')
    ? locale === 'en' ? 'Rulebook search' : '规则检索'
    : normalized.includes('CONTEXT') || normalized.includes('VERSION') || normalized.includes('PUBLIC_LESSON')
      ? locale === 'en' ? 'Answer context' : '答疑上下文'
      : normalized.includes('TRANSPORT') || normalized.includes('REQUEST_FAILED')
        ? locale === 'en' ? 'Answer transport' : '答疑传输'
        : locale === 'en' ? 'Answer Agent' : '答疑 Agent'
  return { category, owner, code: codeOrStatus || null }
}

export function recommendationFailureDescriptor(
  reason: string | null,
  detailCode: string | null,
  boundary: string | null,
  locale: AppLocale,
): PlayerFailureDescriptor {
  const identifier = detailCode || reason || boundary || null
  const normalized = `${reason ?? ''}:${detailCode ?? ''}:${boundary ?? ''}`.toUpperCase()
  const category = normalized.includes('MODEL_NOT_CONFIGURED')
    || normalized.includes('SERVICE_CONFIGURATION')
    ? 'repair-required'
    : normalized.includes('PROTOCOL')
        || normalized.includes('REPEATED_INVALID')
        || normalized.includes('REPEATED_INCOMPATIBLE')
        || normalized.includes('PUBLICATION_REJECTED')
      ? 'internal-correction'
      : 'retry-preserved'
  const owner = normalized.includes('PROVIDER')
    ? locale === 'en' ? 'Model provider' : '模型提供方'
    : normalized.includes('CONFIGURATION') || normalized.includes('NOT_CONFIGURED')
      ? locale === 'en' ? 'Model configuration' : '模型配置'
      : normalized.includes('PUBLICATION')
        ? locale === 'en' ? 'Recommendation publication' : '推荐发布边界'
        : locale === 'en' ? 'Recommendation Agent' : '推荐 Agent'
  return { category, owner, code: identifier }
}

export function teachingFailureOwner(code: string, locale: AppLocale) {
  const normalized = code.toUpperCase()
  if (normalized.includes('QUEUE') || normalized.includes('WORKER') || normalized.includes('ADMISSION')) {
    return locale === 'en' ? 'Background scheduler' : '后台调度'
  }
  if (normalized.includes('PERSISTENCE') || normalized.includes('STORAGE') || normalized.includes('COMPLETION')) {
    return locale === 'en' ? 'Guide persistence' : '讲解保存'
  }
  if (normalized.includes('SOURCE') || normalized.includes('PDF') || normalized.includes('DOCUMENT')) {
    return locale === 'en' ? 'Rulebook source' : '规则书来源'
  }
  if (normalized.includes('VISUAL') || normalized.includes('IMAGE')) {
    return locale === 'en' ? 'Visual enrichment' : '配图处理'
  }
  return locale === 'en' ? 'Guide Agent' : '讲解 Agent'
}
