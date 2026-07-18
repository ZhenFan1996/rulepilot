import { expect, test } from '@playwright/test'

const cachedKnowledge = {
  version: 1,
  entries: [
    {
      question: '硬币如何计分？',
      sectionTitle: '结束条件与计分',
      cachedAt: '2026-07-18T10:00:00.000Z',
      answer: {
        status: 'ANSWERED',
        shortVerdict: '每枚剩余硬币计一分。',
        explanation: '终局时计算玩家剩余硬币。',
        citations: [{ chunkId: 'chunk-1', sectionType: 'SCORING', heading: '计分', excerpt: '每枚硬币一分。', pageFrom: 5, pageTo: 5 }],
        exceptions: [],
        confidence: 'HIGH',
        official: false,
        confirmedRulingId: 'ruling-1',
        confirmedRulingVersion: 1,
        clarification: null,
      },
      ruling: {
        id: 'ruling-1',
        shortVerdict: '每枚剩余硬币计一分。',
        explanation: '终局时计算玩家剩余硬币。',
        citations: [{ chunkId: 'chunk-1', sectionType: 'SCORING', heading: '计分', excerpt: '每枚硬币一分。', pageFrom: 5, pageTo: 5 }],
        exceptions: [],
        confidence: 'HIGH',
        status: 'CONFIRMED',
        version: 1,
      },
    },
  ],
}

test('reloads the app offline and exposes only cached rule knowledge', async ({ context, page }) => {
  const answerRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/answers')) answerRequests.push(request.url())
  })

  await page.goto('/')
  await page.evaluate(async () => navigator.serviceWorker.ready)
  await page.reload()
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true)
  await page.evaluate((knowledge) => {
    localStorage.setItem('rulepilot:offline-knowledge:plan-offline', JSON.stringify(knowledge))
    localStorage.setItem('rulepilot:last-plan-id', 'plan-offline')
  }, cachedKnowledge)

  await context.setOffline(true)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/lesson/plan-offline')

  await expect(page.getByText('当前离线；只能查看本地讲解进度、最近答案和已确认裁定，生成式答疑已停用。')).toBeVisible()
  await expect(page.getByRole('heading', { name: '本局已缓存规则结论' })).toBeVisible()
  await expect(page.getByText('硬币如何计分？')).toBeVisible()
  await expect(page.getByText(/已确认裁定 ·/)).toBeVisible()
  expect(answerRequests).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false)

  if (process.env.PW_SCREENSHOTS) {
    await page.screenshot({ path: 'output/playwright/p11-04-offline-mobile.png', fullPage: true })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.screenshot({ path: 'output/playwright/p11-04-offline-desktop.png', fullPage: true })
  }
})
