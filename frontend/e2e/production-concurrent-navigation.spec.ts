import { access, writeFile } from 'node:fs/promises'

import { expect, test, type Browser, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_EXPERIENCE === 'true'

interface Credentials {
  username: string
  password: string
}

interface NavigationMeasurement {
  user: string
  path: string
  status: number
  durationMs: number
}

async function login(browser: Browser, credentials: Credentials) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/login')
  await page.locator('input[name="username"]').fill(credentials.username)
  await page.locator('input[name="password"]').fill(credentials.password)
  await page.locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(/\/account$/)
  return { context, page }
}

async function finished(path: string) {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}

async function visit(page: Page, user: string, path: string): Promise<NavigationMeasurement> {
  const startedAt = performance.now()
  const response = await page.goto(path, { waitUntil: 'domcontentloaded', timeout: 30_000 })
  const durationMs = Math.round(performance.now() - startedAt)
  expect(response, `${user} did not receive a document response for ${path}`).not.toBeNull()
  expect(response!.status(), `${user} received HTTP ${response!.status()} for ${path}`).toBeLessThan(500)
  await expect(page, `${user} was signed out while visiting ${path}`).not.toHaveURL(/\/login$/)
  await expect(page.locator('body')).not.toBeEmpty()
  return { user, path, status: response!.status(), durationMs }
}

test.skip(!enabled, 'Runs only through the credentialed production experience workflow')

test('two signed-in users can browse while real lessons generate', async ({ browser }, testInfo) => {
  test.setTimeout(25 * 60_000)
  const completionFile = process.env.RULEPILOT_EXPERIENCE_COMPLETION_FILE
  const reportFile = process.env.RULEPILOT_EXPERIENCE_BROWSER_REPORT
  const userA = process.env.RULEPILOT_EXPERIENCE_USER_A
  const passwordA = process.env.RULEPILOT_EXPERIENCE_PASSWORD_A
  const userB = process.env.RULEPILOT_EXPERIENCE_USER_B
  const passwordB = process.env.RULEPILOT_EXPERIENCE_PASSWORD_B
  if (!completionFile || !reportFile || !userA || !passwordA || !userB || !passwordB) {
    throw new Error('Production experience browser credentials and output paths are required')
  }

  const sessionA = await login(browser, { username: userA, password: passwordA })
  const sessionB = await login(browser, { username: userB, password: passwordB })
  const routes = ['/teach', '/lessons', '/catalog', '/library', '/account']
  const measurements: NavigationMeasurement[] = []
  const pageErrors: string[] = []
  let backgroundStatusSeenA = false
  let backgroundStatusSeenB = false
  sessionA.page.on('pageerror', error => pageErrors.push(`A: ${error.message}`))
  sessionB.page.on('pageerror', error => pageErrors.push(`B: ${error.message}`))

  try {
    const deadline = Date.now() + 22 * 60_000
    let index = 0
    while (Date.now() < deadline && !(await finished(completionFile))) {
      const routeA = routes[index % routes.length]
      const routeB = routes[(index + 2) % routes.length]
      const [measurementA, measurementB] = await Promise.all([
        visit(sessionA.page, 'A', routeA),
        visit(sessionB.page, 'B', routeB),
      ])
      measurements.push(measurementA, measurementB)
      backgroundStatusSeenA ||= await sessionA.page.locator('aside[aria-live]').isVisible()
      backgroundStatusSeenB ||= await sessionB.page.locator('aside[aria-live]').isVisible()
      index += 1
      await sessionA.page.waitForTimeout(1_000)
    }

    expect(await finished(completionFile), 'Concurrent generation did not signal completion').toBe(true)
    expect(measurements.length, 'No production navigation measurements were collected').toBeGreaterThan(4)
    expect(pageErrors, 'The production pages emitted uncaught browser errors').toEqual([])
    expect(backgroundStatusSeenA, 'User A never saw persistent generation status while browsing').toBe(true)
    expect(backgroundStatusSeenB, 'User B never saw persistent generation status while browsing').toBe(true)

    await sessionA.page.screenshot({ path: testInfo.outputPath('user-a-final-page.png'), fullPage: true })
    await sessionB.page.screenshot({ path: testInfo.outputPath('user-b-final-page.png'), fullPage: true })
    await writeFile(reportFile, `${JSON.stringify({
      generatedAt: new Date().toISOString(),
      measurements,
      backgroundStatusSeen: { userA: backgroundStatusSeenA, userB: backgroundStatusSeenB },
      pageErrors,
    }, null, 2)}\n`, { mode: 0o600 })
  } finally {
    await sessionA.context.close()
    await sessionB.context.close()
  }
})
