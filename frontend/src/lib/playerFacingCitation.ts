const internalEnvelope = /^Visual(?:-transcribed rule evidence| page facts)[\s\S]*?\nVisible(?: rule)? facts: /
const extractedPageTextLabel = /\n\nExtracted page text [^\n]*\n/

export function playerFacingCitationExcerpt(value: string): string {
  const cleaned = value.replace(internalEnvelope, '')
  return cleaned === value ? value : cleaned.replace(extractedPageTextLabel, '\n\n').trim()
}
