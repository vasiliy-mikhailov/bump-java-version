import { defineConfig, devices } from '@playwright/test'

/**
 * AGAINST THE DEPLOYMENT, not a dev server.
 *
 * These shots are evidence about what a reader sees, and a reader sees the container: the static
 * export served from the jar, behind the proxy that authenticates. A `next dev` server would render
 * the same components against a different asset pipeline and prove less than it appeared to.
 *
 * The basic-auth credential is the proxy's, supplied by the environment. This tool has no login of
 * its own by design — the shell authenticates and only reaches it for a request it has authorised —
 * so out here in front of the shell, the proxy is what stands in for that.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  // Serially, so four browsers do not hammer a box that is also running four bump lanes.
  workers: 1,
  reporter: [['list']],
  use: {
    // THE SPREAD GOES FIRST. It carries a viewport and a scale of its own, so anything set above it
    // is silently discarded — which is how a shot meant to be 1600 wide at 2x arrives 1280 at 1x,
    // looking like a layout bug in the page rather than a mistake in this file.
    ...devices['Desktop Chrome'],
    baseURL: process.env.BJV_URL ?? 'https://bump-java-version.mikhailov.tech',
    ...(process.env.BJV_USER
      ? {
          httpCredentials: {
            username: process.env.BJV_USER,
            password: process.env.BJV_PASS ?? '',
          },
        }
      : {}),
    viewport: { width: 1600, height: 1000 },
    // The shots are for a person to compare with a screenshot of the sibling taken on a laptop.
    deviceScaleFactor: 2,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
