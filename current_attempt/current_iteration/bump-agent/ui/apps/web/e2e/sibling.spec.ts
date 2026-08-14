import { test } from '@playwright/test'

/** The sibling's screens, read-only, for comparison. Nothing here touches that repository. */
const BASE = 'https://fix-java-svace-markers.mikhailov.tech'

for (const tab of ['prompts', 'run', 'model', 'subject', 'supervisor']) {
  test(`sibling settings: ${tab}`, async ({ page }) => {
    await page.goto(`${BASE}/settings?a=${tab}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ path: `shots/sibling-settings-${tab}.png`, fullPage: false })
  })
}
