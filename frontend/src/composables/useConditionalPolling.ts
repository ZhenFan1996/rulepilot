interface UseConditionalPollingOptions {
  enabled: () => boolean
  refresh: () => void | Promise<void>
  defaultDelay: number
}

export function useConditionalPolling(options: UseConditionalPollingOptions) {
  let refreshTimer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function clear() {
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = undefined
  }

  function schedule(delay = options.defaultDelay) {
    clear()
    if (disposed || !options.enabled()) return
    refreshTimer = setTimeout(() => {
      refreshTimer = undefined
      void options.refresh()
    }, delay)
  }

  function dispose() {
    disposed = true
    clear()
  }

  return {
    clear,
    schedule,
    dispose,
  }
}
