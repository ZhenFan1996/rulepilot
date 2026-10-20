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
  if (category === 'local-degradation') return {
    title: english ? 'Some supporting content is unavailable' : '部分辅助内容暂不可用',
    detail: english ? 'Your available content is preserved.' : '已经完成的内容已保留。',
  }
  if (category === 'retry-preserved') return {
    title: english ? 'You can continue' : '可以继续生成',
    detail: english ? 'Your progress is saved. Continue to finish the missing content.' : '进度已保存，继续生成即可补齐未完成的内容。',
  }
  if (category === 'repair-required') return {
    title: english ? 'Temporarily unavailable' : '暂时无法继续',
    detail: english ? 'Your progress is saved. Try again later.' : '进度已保存，请稍后再试。',
  }
  return {
    title: english ? 'This part is not ready yet' : '这部分尚未完成',
    detail: english ? 'Your available content is preserved. You do not need to rewrite your request.' : '已完成的内容会保留，无需改写你的问题。',
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
