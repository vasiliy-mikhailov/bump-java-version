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

    // BY NAME, not by position: the pair tables are nested inside this tbody, so `tbody tr`
    // matches both levels and would compare a package against a version pair.
    const rows = page.locator('tr[data-row="package"]')
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

  test('each package decomposes into the version pairs behind it', async ({ page }) => {
    // THE SECOND LEVEL. "tomcat-embed-core 238 -> 81" says the corpus cleared 157 and does not say
    // which upgrade did it, and the same package is both moved and stuck across a corpus. The pair
    // is the level a reader can act on, so it has to be reachable and it has to be ordered.
    await page.goto('/security/')
    await page.waitForSelector('table tbody tr', { timeout: 30_000 })

    const folds = page.locator('details')
    expect(await folds.count(), 'no package decomposes').toBeGreaterThan(1)

    const first = folds.first()
    await first.locator('summary').click()
    const inner = first.locator('table')
    await expect(inner).toBeVisible()

    // The columns the decomposition exists for: which version, to which version.
    await expect(inner.locator('th').filter({ hasText: 'from' })).toBeVisible()
    await expect(inner.locator('th').filter({ hasText: 'to' })).toBeVisible()

    const rows = inner.locator('tr[data-row="pair"]')
    const n = await rows.count()
    expect(n, 'a package that cleared anything moved from at least one version').toBeGreaterThan(0)

    const cleared = (await rows.locator('td:last-child').allInnerTexts()).map((s) => {
      const v = s.trim()
      if (v.startsWith('−')) return Number.parseInt(v.slice(1), 10)
      if (v.startsWith('+')) return -Number.parseInt(v.slice(1), 10)
      return 0
    })
    for (let i = 1; i < cleared.length; i += 1) {
      expect(cleared[i - 1] ?? 0, `pair ${i} out of order`).toBeGreaterThanOrEqual(cleared[i] ?? 0)
    }
    console.log(`  ${n} version pairs, best cleared ${cleared[0]}, worst ${cleared[cleared.length - 1]}`)
  })

  test('a pair that went nowhere is shown, not hidden', async ({ page }) => {
    // The rows worth acting on are the ones at the BOTTOM: a dependency that arrived and left at
    // the same version, still carrying its findings. A table that only showed wins would be an
    // advert rather than a report.
    await page.goto('/security/')
    await page.waitForSelector('details', { timeout: 30_000 })
    await page.locator('details summary').first().click()
    const rows = page.locator('details').first().locator('tr[data-row="pair"]')
    const texts = await rows.allInnerTexts()
    const stuck = texts.filter((t) => {
      const cells = t.split(/\t|\n/).map((s) => s.trim()).filter(Boolean)
      return cells.length >= 2 && cells[0] === cells[1]
    })
    expect(stuck.length, 'no unmoved pair listed; the table is only showing wins').toBeGreaterThan(0)
  })
})
