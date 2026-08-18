import type { Verdict } from '@bjv/types'
import { Pill, type PillTone } from '../primitives/Pill'

export type VerdictPillProps = { verdict: Verdict; href?: string }

/**
 * THE ONE PLACE A VERDICT BECOMES A COLOUR.
 *
 * The failures are not interchangeable and must not read alike. A lost test is a REGRESSION this
 * bump caused; a dependency with no release for this JDK is a fact about the world. Colouring them
 * the same is how a triage queue becomes a wall of red nobody sorts — this corpus has been triaged
 * by hand twice, and both times most of the red turned out to be environmental.
 */
const TONE: Record<Verdict, PillTone> = {
  PASS: 'good',
  FAIL_test_conservation: 'alarm',
  FAIL_build_post: 'alarm',
  FAIL_target_not_bumped: 'warn',
  FAIL_no_main_bytecode: 'warn',
  NO_BASELINE_NOTESTS: 'quiet',
  'no-baseline': 'quiet',
  'blocked-dependency': 'aside',
  'behavior-change': 'warn',
  infra: 'quiet',
  bumping: 'running',
  queued: 'quiet',
}

/** What each verdict actually means, on hover. The vocabulary is not self-explanatory. */
const WHY: Record<Verdict, string> = {
  PASS: 'built under the target JDK, kept every test that was passing, and the bytecode reached the target',
  FAIL_test_conservation: 'a test that passed before the bump does not pass after it',
  FAIL_build_post: 'the project no longer builds under the target JDK',
  FAIL_target_not_bumped: 'it builds and tests green, but the lowest module still compiles below the target',
  FAIL_no_main_bytecode: 'the build reported success but produced no classes to inspect',
  NO_BASELINE_NOTESTS: 'no test passed under the project’s own JDK, so there is nothing to conserve',
  'no-baseline': 'the project does not build under its own JDK, so the bump is unverifiable',
  'blocked-dependency': 'a dependency this project needs has no release that runs on the target JDK',
  'behavior-change': 'reaching the target would require changing what the program does',
  infra: 'the harness or the environment failed, not the project',
  bumping: 'still running',
  queued: 'in the manifest, waiting for a lane',
}

export function VerdictPill({ verdict, href }: VerdictPillProps) {
  // Spread rather than pass undefined: Pill's `href` means "absent", not "present and undefined",
  // and a span is a different element from an anchor with no destination.
  return (
    <Pill tone={TONE[verdict]} {...(href === undefined ? {} : { href })} title={WHY[verdict]}>
      {verdict}
    </Pill>
  )
}
