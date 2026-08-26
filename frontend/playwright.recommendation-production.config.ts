import { defineConfig, devices } from '@playwright/test'

const canaryTraceparent = process.env.RULEPILOT_RECOMMENDATION_TRACEPARENT ?? ''
const traceparentMatch = canaryTraceparent.match(
  /^00-([0-9a-f]{32})-([0-9a-f]{16})-01$/,
)
if (!traceparentMatch
  || /^0{32}$/.test(traceparentMatch[1])
  || /^0{16}$/.test(traceparentMatch[2])) {
  throw new Error('RULEPILOT_RECOMMENDATION_TRACEPARENT must be a sampled non-zero W3C version 00 value')
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'production-recommendation-journey.spec.ts',
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.RULEPILOT_PRODUCTION_BASE_URL,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    extraHTTPHeaders: {
      traceparent: canaryTraceparent,
    },
    ...devices['Desktop Chrome'],
  },
})
