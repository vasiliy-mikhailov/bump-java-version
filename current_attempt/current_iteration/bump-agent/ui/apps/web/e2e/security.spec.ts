import { expect, test } from '@playwright/test'

/**
 * THE RATE ON THE LIST OPENS THE PAGE THAT JUSTIFIES IT.
 *
 * "24% removed" is a number to believe or not until something says which dependencies account for
 * it. These walk that path and check the page can actually answer the question, rather than that
 * it renders.
 */
const SHOTS = process.env.SHOTS ?? 'shots'

test.describe('the corpus security drill-down', () => {
  test('the removal rate on the list opens it', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('table', { timeout: 30_000 })

    const tally = page.locator('a[href*="/security"]').first()
    await expect(tally).toBeVisible({ timeout: 30_000 })
    await expect(tally).toContainText('removed')

    await tally.click()
    await page.waitForURL(/\/security/, { timeout: 30_000 })
    await expect(page.getByRole('heading', { name: 'vulnerabilities cleared' })).toBeVisible()
    await page.waitForSelector('table', { timeout: 30_000 })

    await page.screenshot({ path: `${SHOTS}/08-security.png`, fullPage: false })
  })

  test('it names the packages the clearing came from, worst-cleared last', async ({ page }) => {
    await page.goto('/security/')
    await page.waitForSelector('table tbody tr', { timeout: 30_000 })

    const rows = page.locator('table').first().locator('tbody tr')
    const n = await rows.count()
    // A corpus that has cleared anything has cleared it from more than one package. One row means
    // the aggregation did not run, not that the answer is short.
    expect(n, 'too few packages for the aggregation to have run').toBeGreaterThan(1)

    // CLEARED IS NON-INCREASING, which is the ordering claim the page makes in its own heading.
    const cleared = (
      await rows.locator('td:last-child').allInnerTexts()
    ).map((s) => {
      const t = s.trim()
      if (t.startsWith('−')) return Number.parseInt(t.slice(1), 10)
      if (t.startsWith('+')) return -Number.parseInt(t.slice(1), 10)
      return 0
    })
    for (let i = 1; i < cleared.length; i += 1) {
      expect(cleared[i - 1] ?? 0, `row ${i} out of order`).toBeGreaterThanOrEqual(cleared[i] ?? 0)
    }
    console.log(`  checked ${cleared.length} packages, best cleared ${cleared[0]}`)
  })

  test('it reconciles its own total with the one on the list', async ({ page }) => {
    // THE TWO HEADLINES DISAGREE ON PURPOSE: distinct here, occurrences there. Without the
    // sentence saying so, a reader who noticed would be right to distrust both.
    await page.goto('/security/')
    await page.waitForSelector('table', { timeout: 30_000 })
    const body = (await page.textContent('body')) ?? ''
    expect(body).toContain('occurrence-based')
    expect(body).toContain('The list page reports')
  })

  test('a bump on it leads to that bump’s own dependency table', async ({ page }) => {
    await page.goto('/security/')
    await page.waitForSelector('table', { timeout: 30_000 })
    const link = page.locator('a[href*="/bump/"]').first()
    await expect(link).toBeVisible({ timeout: 30_000 })
    await link.click()
    await page.waitForURL(/#dependencies/, { timeout: 30_000 })
    await expect(page.locator('#dependencies')).toBeVisible()
  })
})
