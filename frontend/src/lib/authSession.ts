export const LOGIN_REQUIRED_EVENT = 'rulepilot:login-required'
export const SESSION_CLEARED_EVENT = 'rulepilot:session-cleared'
const AUTH_RETURN_ORIGIN = 'https://rulepilot.invalid'
const AUTHENTICATION_PATHS = new Set(['/login', '/register'])

export function notifyLoginRequired() {
  window.dispatchEvent(new Event(LOGIN_REQUIRED_EVENT))
}

export function notifySessionCleared() {
  window.dispatchEvent(new Event(SESSION_CLEARED_EVENT))
}

export function safeAuthReturnPath(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('/')) return null

  try {
    const resolved = new URL(value, AUTH_RETURN_ORIGIN)
    if (resolved.origin !== AUTH_RETURN_ORIGIN) return null

    const decodedPath = decodeURIComponent(resolved.pathname)
    if (decodedPath.startsWith('//') || decodedPath.startsWith('/\\')) return null
    const normalizedPath = (decodedPath.replace(/\/+$/, '') || '/').toLowerCase()
    if (AUTHENTICATION_PATHS.has(normalizedPath)) return null

    return `${resolved.pathname}${resolved.search}${resolved.hash}`
  } catch {
    return null
  }
}

export const safeLoginReturnPath = safeAuthReturnPath
