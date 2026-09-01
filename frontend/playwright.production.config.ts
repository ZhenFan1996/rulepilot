import { defineConfig, devices } from '@playwright/test'

const privateOutputDirectory = process.env.RULEPILOT_PRODUCTION_PLAYWRIGHT_OUTPUT_DIR
  ?? 'test-results'
const privateReportDirectory = process.env.RULEPILOT_PRODUCTION_PLAYWRIGHT_REPORT_DIR
  ?? 'playwright-production-report'

export default defineConfig({
  testDir: './e2e',
  testMatch: 'production-concurrent-navigation.spec.ts',
  forbidOnly: true,
  retries: 0,
  workers: 1,
  outputDir: privateOutputDirectory,
  reporter: [['list'], ['html', { outputFolder: privateReportDirectory, open: 'never' }]],
  use: {
    baseURL: process.env.RULEPILOT_PRODUCTION_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
})
