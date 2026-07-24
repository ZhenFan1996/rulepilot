export function publicCoverUrl(planId: string): string | undefined {
  if (!planId) return undefined
  return `/api/public/lessons/${encodeURIComponent(planId)}/cover`
}
