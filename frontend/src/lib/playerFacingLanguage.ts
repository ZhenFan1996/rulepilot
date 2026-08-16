import type { AppLocale } from '@/lib/locale'

const simplifiedChineseRegions = new Set(['CN', 'SG'])
const traditionalChineseRegions = new Set(['TW', 'HK', 'MO'])

export function playerFacingLanguageName(value: unknown, locale: AppLocale): string {
  const unknown = locale === 'zh-CN' ? '语言未标注' : 'Language not stated'
  if (typeof value !== 'string') return unknown
  const candidate = value.trim().replaceAll('_', '-')
  if (!candidate || candidate.toLowerCase() === 'und') return unknown

  let parsed: Intl.Locale
  try {
    parsed = new Intl.Locale(candidate)
  } catch {
    return unknown
  }
  if (parsed.language.toLowerCase() === 'und') return unknown

  if (parsed.language.toLowerCase() === 'en') return locale === 'zh-CN' ? '英文' : 'English'
  if (parsed.language.toLowerCase() === 'zh') {
    const simplified = parsed.script === 'Hans' || simplifiedChineseRegions.has(parsed.region ?? '')
    const traditional = parsed.script === 'Hant' || traditionalChineseRegions.has(parsed.region ?? '')
    if (simplified) return locale === 'zh-CN' ? '简体中文' : 'Simplified Chinese'
    if (traditional) return locale === 'zh-CN' ? '繁体中文' : 'Traditional Chinese'
    return locale === 'zh-CN' ? '中文' : 'Chinese'
  }

  try {
    const name = new Intl.DisplayNames([locale], { type: 'language' }).of(parsed.baseName)
    if (!name || name.toLowerCase() === candidate.toLowerCase()) return unknown
    return name
  } catch {
    return unknown
  }
}
