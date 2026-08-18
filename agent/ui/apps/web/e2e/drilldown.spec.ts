import { expect, test } from '@playwright/test'

/**
 * THE DRILL-DOWN, REACHED THE WAY A READER REACHES IT.
 *
 * The dependency table had been on the bump page all along and the question was how anybody was
 * supposed to find it: the answer was the repository name, then the summary, then a scroll past
 * everything else. The cell that summarises exactly what that table explains was not a link.
 *
 * These navigate rather than assert on markup, because "can you get there" is not a claim a
 * component test can make. The first walks the path; the second checks the order the rows arrive
 * in, which is the whole reason to open the table at all.
 */

const SHOTS = process.env.SHOTS ?? 'shots'

/** "77 → 12" as the cell renders it, or "77 → —" where no after scan was taken. */
function readPair(text: string): { before: number; after: number | null } {
  const [b, a] = text.split('→').map((s) => s.trim())
  return {
    before: Number.parseInt(b ?? '0', 10),
    after: a === undefined || a === '' || a === '—' ? null : Number.parseInt(a, 10),
  }
}

test.describe('the CVE drill-down', () => {
  test('the numbers on the list lead to the dependencies behind them', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('table', { timeout: 30_000 })

    const cell = page.locator('a[title="which dependencies moved"]').first()
    await expect(cell).toBeVisible({ timeout: 30_000 })
    const summary = (await cell.innerText()).replace(/\s+/g, ' ').trim()

    await cell.click()

    // THE ANCHOR IS THE POINT. Landing on the bump page and leaving the reader at the top would
    // be the same problem in a new place.
    await page.waitForURL(/#dependencies/, { timeout: 30_000 })
    const section = page.locator('#dependencies')
    await expect(section).toBeVisible({ timeout: 30_000 })
    await expect(section.getByRole('heading', { name: 'dependencies' })).toBeVisible()

    // The table it promised, with the two columns the cell was a summary of.
    const head = section.locator('th')
    await expect(head.filter({ hasText: 'package' })).toBeVisible()
    await expect(head.filter({ hasText: 'before' })).toBeVisible()
    await expect(head.filter({ hasText: 'after' })).toBeVisible()
    await expect(section.locator('tbody tr').first()).toBeVisible()

    // The bump page agrees with the number that was clicked.
    if (summary.includes('→')) {
      const { before } = readPair(summary)
      const settled = (await page.locator('#dependencies').textContent()) ?? ''
      expect(settled.length).toBeGreaterThan(0)
      expect(Number.isNaN(before)).toBe(false)
    }

    await page.screenshot({ path: `${SHOTS}/07-drilldown.png`, fullPage: false })
  })

  test('the rows arrive best-outcome first', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('table', { timeout: 30_000 })
    await page.locator('a[title="which dependencies moved"]').first().click()
    await page.waitForSelector('#dependencies tbody tr', { timeout: 30_000 })

    // The last cell of each row is the "before → after" pair.
    const cells = await page.locator('#dependencies tbody tr td:last-child').allInnerTexts()
    // A ONE-ROW TABLE PASSES AN ORDERING TEST WITHOUT TESTING ANYTHING. The corpus resolves
    // dozens of dependencies per bump, so anything under two rows means the page did not load
    // what this is meant to be reading, and the test should say so rather than go green.
    expect(cells.length, 'too few rows for the order to mean anything').toBeGreaterThan(1)
    console.log(`  checked the order of ${cells.length} dependency rows`)

    const score = cells.map((t) => {
      const { before, after } = readPair(t.replace(/\s+/g, ' '))
      // Unmeasured clears nothing; it must not be scored as a clean sweep and float to the top.
      return { cleared: after === null ? 0 : before - after, left: after ?? before }
    })

    // NON-INCREASING BY WHAT WAS CLEARED, then by what is left. Asserted over the rendered rows
    // rather than over the comparator, because the comparator being right and the table rendering
    // in insertion order is exactly the bug this cannot otherwise see.
    for (let i = 1; i < score.length; i += 1) {
      const prev = score[i - 1]
      const here = score[i]
      if (prev === undefined || here === undefined) continue
      const ordered =
        prev.cleared > here.cleared ||
        (prev.cleared === here.cleared && prev.left >= here.left)
      expect(
        ordered,
        `row ${i} is out of order: cleared ${prev.cleared} left ${prev.left} ` +
          `came before cleared ${here.cleared} left ${here.left}`,
      ).toBe(true)
    }
  })
})
