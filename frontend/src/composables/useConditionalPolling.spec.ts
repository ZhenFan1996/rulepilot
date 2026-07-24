import { afterEach, describe, expect, it, vi } from 'vitest'

import { useConditionalPolling } from '@/composables/useConditionalPolling'

describe('useConditionalPolling', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('refreshes once after the requested delay when its owner remains enabled', async () => {
    vi.useFakeTimers()
    const refresh = vi.fn(async () => undefined)
    const polling = useConditionalPolling({ enabled: () => true, refresh, defaultDelay: 1_500 })

    polling.schedule()
    await vi.advanceTimersByTimeAsync(1_499)
    expect(refresh).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(refresh).toHaveBeenCalledOnce()
  })

  it('does not schedule work while disabled and cancels a pending refresh', async () => {
    vi.useFakeTimers()
    let enabled = false
    const refresh = vi.fn()
    const polling = useConditionalPolling({ enabled: () => enabled, refresh, defaultDelay: 2_500 })

    polling.schedule()
    await vi.advanceTimersByTimeAsync(2_500)
    expect(refresh).not.toHaveBeenCalled()

    enabled = true
    polling.schedule()
    polling.clear()
    await vi.advanceTimersByTimeAsync(2_500)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('cancels pending work permanently when disposed', async () => {
    vi.useFakeTimers()
    const refresh = vi.fn()
    const polling = useConditionalPolling({ enabled: () => true, refresh, defaultDelay: 2_500 })

    polling.schedule()
    polling.dispose()
    polling.schedule(0)
    await vi.advanceTimersByTimeAsync(2_500)

    expect(refresh).not.toHaveBeenCalled()
  })
})
