const VITE_PRELOAD_ERROR = 'vite:preloadError'

interface NavigationErrorRouter {
  onError(handler: (error: unknown, to: { fullPath: string }) => void): () => void
}

interface BrowserLocation {
  assign(url: string): void
}

export function installStaleAssetRecovery(
  router: NavigationErrorRouter,
  target: Pick<Window, 'addEventListener'> = window,
  location: BrowserLocation = window.location,
) {
  let staleAssetFailed = false

  target.addEventListener(VITE_PRELOAD_ERROR, (event) => {
    // A tab left open across a deployment can still reference a hashed route
    // chunk that the new release no longer serves. Vite marks that failure so
    // the router can load the intended destination from current no-cache HTML.
    event.preventDefault()
    staleAssetFailed = true
  })

  router.onError((_error, to) => {
    if (!staleAssetFailed) return
    staleAssetFailed = false
    location.assign(to.fullPath)
  })
}
