const VITE_PRELOAD_ERROR = 'vite:preloadError'

interface NavigationErrorRouter {
  onError(handler: (error: unknown, to: { fullPath: string }) => void): () => void
  afterEach(handler: (_to: unknown, _from: unknown, failure?: unknown) => void): () => void
}

interface BrowserLocation {
  replace(url: string): void
}

interface RecoveryStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const RECOVERY_ATTEMPT_KEY = 'rulepilot:stale-asset-recovery-attempted'

export function installStaleAssetRecovery(
  router: NavigationErrorRouter,
  target: Pick<Window, 'addEventListener'> = window,
  location: BrowserLocation = window.location,
  recoveryStorage?: RecoveryStorage,
) {
  let staleAssetFailed = false

  function availableRecoveryStorage() {
    if (recoveryStorage) return recoveryStorage
    try {
      return window.sessionStorage
    } catch {
      return undefined
    }
  }

  function recoveryAlreadyAttempted() {
    const storage = availableRecoveryStorage()
    if (!storage) return true
    try {
      return storage.getItem(RECOVERY_ATTEMPT_KEY) !== null
    } catch {
      return true
    }
  }

  function markRecoveryAttempted() {
    const storage = availableRecoveryStorage()
    if (!storage) return
    try {
      storage.setItem(RECOVERY_ATTEMPT_KEY, 'true')
    } catch {
      // A storage-denied browser must not enter a reload loop.
    }
  }

  function clearRecoveryAttempt() {
    const storage = availableRecoveryStorage()
    if (!storage) return
    try {
      storage.removeItem(RECOVERY_ATTEMPT_KEY)
    } catch {
      // Storage availability must never decide whether a resolved route can render.
    }
  }

  target.addEventListener(VITE_PRELOAD_ERROR, (event) => {
    // A tab left open across a deployment can still reference a hashed route
    // chunk that the new release no longer serves. Vite marks that failure so
    // the router can load the intended destination from current no-cache HTML.
    // If the refreshed release still cannot resolve the route, allow Vite to
    // surface the real load error instead of trapping the browser in a reload loop.
    if (recoveryAlreadyAttempted()) return
    event.preventDefault()
    staleAssetFailed = true
  })

  router.onError((_error, to) => {
    if (!staleAssetFailed) return
    staleAssetFailed = false
    markRecoveryAttempted()
    location.replace(to.fullPath)
  })

  router.afterEach((_to, _from, failure) => {
    if (!failure) clearRecoveryAttempt()
  })
}
