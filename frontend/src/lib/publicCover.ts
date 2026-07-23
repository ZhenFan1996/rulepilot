export function publicCoverUrl(planId: string, sourceImageUrl?: string | null): string | undefined {
  if (!planId || !sourceImageUrl) return undefined
  return `/api/public/lessons/${encodeURIComponent(planId)}/cover`
}
