import { defineConfig } from 'vitest/config'

/**
 * VITEST DOES NOT OWN THE E2E DIRECTORY.
 *
 * Its default include matches `*.spec.ts`, which is also Playwright's convention, so `pnpm test`
 * loaded the browser suite into a unit-test runner and failed on an import it has no business
 * resolving. The two runners answer different questions — one about a component in isolation, one
 * about the deployment — and the only thing they need to agree on is which files belong to whom.
 */
export default defineConfig({
  test: { environment: 'happy-dom', globals: true, exclude: ['e2e/**', 'node_modules/**'] },
})
