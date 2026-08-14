import { expect, test } from '@playwright/test'

/**
 * WHAT THE PAGES ACTUALLY LOOK LIKE, against the deployment rather than a fixture.
 *
 * A component test proves a table renders a module count; it cannot prove the page reads as the
 * same application as the tool next to it in the shell. That is a question about type, spacing and
 * where the page starts, and the only honest way to answer it is to look — so these navigate the
 * real deployment, with the real record behind it, and leave the images somewhere a person can
 * compare them side by side with the sibling.
 *
 * They also assert the few things worth failing a build over. A screenshot nobody opens is not a
 * test; the expectations below are what the shots are evidence FOR.
 */

const SHOTS = process.env.SHOTS ?? 'shots'

test.describe('bump-java-version, as a reader sees it', () => {
  test('the bumps list', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'bumps' })).toBeVisible()
    // The record loads client-side; wait for the table rather than for a timer.
    await page.waitForSelector('table', { timeout: 30_000 })

    // THE HOUSE STYLE, asserted against the rendered document rather than the source. A build that
    // shipped the right CSS and the wrong font stack would pass every other test in this repository.
    const font = await page.evaluate(() => getComputedStyle(document.body).fontFamily)
    expect(font).toContain('ui-monospace')

    await page.screenshot({ path: `${SHOTS}/01-bumps.png`, fullPage: false })
  })

  test('one bump: the chain, drawn as triplets', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('table a', { timeout: 30_000 })
    await page.locator('table a').first().click()
    await page.waitForSelector('text=the chain', { timeout: 30_000 })

    // The thing the redesign exists for: three roles per stage, not two.
    //
    // SCOPED TO THE STRIP. An unscoped `getByText('survey-planner')` matches seven nodes, because
    // the event feed names the agent that spoke on every line it wrote — which is a strict-mode
    // failure that reads like a page bug and is not one. The strip is the first section; the feed
    // is further down and is a different question.
    const strip = page.locator('section').first()
    await expect(strip.getByText('survey-planner')).toBeVisible()
    await expect(strip.getByText('survey-doer')).toBeVisible()
    await expect(strip.getByText('survey-verifier')).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/02-bump.png`, fullPage: false })
  })

  for (const tab of ['prompts', 'run', 'model', 'subject', 'supervisor']) {
    test(`settings: ${tab}`, async ({ page }) => {
      await page.goto(`/settings/?a=${tab}`)
      // Every section titles itself, so the header is the assertion that the tab actually routed.
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      await page.waitForSelector('section, article', { timeout: 30_000 })
      await page.screenshot({ path: `${SHOTS}/05-settings-${tab}.png`, fullPage: false })
    })
  }

  test('a registry can be uploaded, and a bad line comes back', async ({ page }) => {
    await page.goto('/settings/?a=subject')
    await page.waitForSelector('section', { timeout: 30_000 })

    await expect(page.getByText('load a registry')).toBeVisible()
    // The paste path exists so a reader with three rows does not have to make a file for them.
    await page.getByText('paste a list instead').click()
    await page
      .locator('textarea')
      // Two DIFFERENT failures, so the report is shown to distinguish them rather than to count.
      // "not a row at all" would not do: it is five words, so it passes the field count and fails
      // on the repo shape — which is the parser being right and the test being lazy.
      .fill('rr_9_9\towner/name\tmain\t11\t17\noops')
    await page.getByRole('button', { name: 'save' }).click()

    // BOTH lines are reported, with a reason each. A loader that answered "ok" to a file it had
    // discarded half of is the failure this whole path is built against.
    await expect(page.getByText('2 line(s) not loaded')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByText(/sha should be a commit/)).toBeVisible()
    await expect(page.getByText(/needs 5 fields/)).toBeVisible()

    await page.screenshot({ path: `${SHOTS}/06-registry-upload.png`, fullPage: false })
  })

  test('the model page never renders the key', async ({ page }) => {
    // The sibling's mount contract flags its own key-rendering as the part a shell author must read
    // twice. This tool takes the other side of that trade, and this is what holds it there.
    await page.goto('/settings/?a=model')
    await page.waitForSelector('section', { timeout: 30_000 })
    const body = (await page.textContent('body')) ?? ''
    expect(body).toContain('key set')
    // Nothing on the page may look like a credential.
    expect(body).not.toMatch(/sk-[A-Za-z0-9]{8}/)
    const inputs = await page.locator('input[type="password"]').count()
    expect(inputs).toBe(0)
  })

  test('the prompts, per hop', async ({ page }) => {
    await page.goto('/settings/?a=prompts')
    await page.waitForSelector('article', { timeout: 30_000 })
    await expect(page.getByRole('heading', { name: 'prompts' })).toBeVisible()

    // GROUPED BY STAGE, in the order the chain reaches them. The list is 34 cards; ungrouped it is
    // a wall, and the stage heading is the only thing that says which triple a card belongs to.
    await expect(page.getByRole('heading', { name: 'survey' })).toBeVisible()

    // ONE HOP SELECTOR. There were two — a tab row and a row of pills, the same four hops twice.
    const hops = page.getByRole('link', { name: /→/ })
    await expect(hops).toHaveCount(4)

    await page.screenshot({ path: `${SHOTS}/03-settings.png`, fullPage: false })
  })

  test('the hop is in the address bar, so a reader can send it to somebody', async ({ page }) => {
    await page.goto('/settings/?a=prompts&hop=21-25')
    await page.waitForSelector('article', { timeout: 30_000 })

    // The JDK 25 lombok, which no lower hop is shown, is the cheapest proof the hop was honoured.
    await expect(page.getByText('1.18.46').first()).toBeVisible()
  })

  test('dark, which the shell decides and this zone follows', async ({ page, context }) => {
    // The contract: the shell writes `theme` on the shared origin, and a zone renders its own
    // document, so this is the only thing that carries a theme across the boundary.
    await context.addCookies([
      { name: 'theme', value: 'dark', url: 'https://bump-java-version.mikhailov.tech' },
    ])
    await page.goto('/')
    await page.waitForSelector('table', { timeout: 30_000 })

    const dark = await page.evaluate(() => document.documentElement.classList.contains('dark'))
    expect(dark).toBe(true)

    await page.screenshot({ path: `${SHOTS}/04-bumps-dark.png`, fullPage: false })
  })
})
