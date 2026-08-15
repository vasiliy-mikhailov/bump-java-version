import type { BumpSummary } from '@bjv/types'

export type CveTotals = {
  /** CRITICAL+HIGH before the bump, summed over bumps that have BOTH numbers. */
  before: number
  after: number
  removed: number
  /** Percent of `before` cleared, or null when nothing has been measured yet. */
  rate: number | null
  /** How many bumps those totals cover. Never the size of the corpus. */
  measured: number
}

/**
 * THE CORPUS'S CVE MOVEMENT, OVER THE BUMPS THAT ACTUALLY HAVE ONE.
 *
 * BOTH NUMBERS OR NEITHER, and that rule is the whole reason this is a function rather than two
 * reduces at the call site. Summing every known `before` and every known `after` independently
 * looks equivalent and is not: the after scan only runs on a green gate, deliberately, because on
 * any other exit the offline collect copies whatever resolved before the build died and the count
 * falls because modules are missing rather than because anything was fixed. So a failed bump has a
 * before and no after. Adding its before to one column and nothing to the other would credit the
 * run with clearing every vulnerability in a project it never finished, and the harder the repo the
 * larger the phantom win.
 *
 * The count of what was measured travels with the totals for the same reason. "24 removed" over
 * three bumps and over four hundred are different claims, and a number on a dashboard with no
 * denominator gets read as the corpus.
 */
export function cveTotals(bumps: readonly BumpSummary[]): CveTotals {
  let before = 0
  let after = 0
  let measured = 0
  for (const b of bumps) {
    if (b.cvesBefore === null || b.cvesAfter === null) {
      continue
    }
    before += b.cvesBefore
    after += b.cvesAfter
    measured += 1
  }
  const removed = before - after
  return {
    before,
    after,
    removed,
    // A rate needs something to be a rate OF. Zero before is not zero percent cleared.
    rate: before === 0 ? null : Math.round((removed * 100) / before),
    measured,
  }
}
