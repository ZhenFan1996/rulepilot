export const LOGIN_REQUIRED_EVENT = 'rulepilot:login-required'

export function notifyLoginRequired() {
  window.dispatchEvent(new Event(LOGIN_REQUIRED_EVENT))
}

export function safeLoginReturnPath(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null
  if (value === '/login' || value.startsWith('/login?')) return null
  return value
}
