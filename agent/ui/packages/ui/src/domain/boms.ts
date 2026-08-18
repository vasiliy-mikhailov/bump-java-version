import type { BumpSummary } from '@bjv/types'

export type BomTotals = {
  /** Floors below their version before the bump, over the repositories judgeable on both sides. */
  before: number
  /** The same floors after it. */
  after: number
  /** Per cent of those issues closed, or null when there were none to close. */
  removed: number | null
  /** Floors met over floors that applied, as things stand. Null when nothing applied. */
  compliance: number | null
  /** How many bumps carry a comparable pair, because the rate is only over those. */
  measured: number
}

/**
 * THE CORPUS SEEN THROUGH THE BILLS OF MATERIALS, in the same shape as the CVE tiles beside it.
 *
 * TWO DIFFERENT DENOMINATORS ON PURPOSE. Compliance is over everything measurable right now, which
 * is the question "how much of what the target needs has this corpus reached". The before-and-after
 * pair is over floors that were judgeable on BOTH sides, which is the question "how much of that
 * did these bumps do", and it is a strictly smaller set: the after-scan only runs on a green gate,
 * so a bump that never reached one has a resolved tree before and none after. Subtracting those
 * totals would report the missing measurement as work done, which is the most expensive recurring
 * mistake in this project wearing a percentage.
 */
export function bomTotals(bumps: BumpSummary[]): BomTotals {
  let before = 0
  let after = 0
  let met = 0
  let applied = 0
  let measured = 0

  for (const b of bumps) {
    if (b.bomMet != null && b.bomMissed != null && b.bomMet + b.bomMissed > 0) {
      met += b.bomMet
      applied += b.bomMet + b.bomMissed
    }
    if (
      b.bomPairApplied == null ||
      b.bomPairApplied === 0 ||
      b.bomPairMissedBefore == null ||
      b.bomPairMissedAfter == null
    ) {
      continue
    }
    before += b.bomPairMissedBefore
    after += b.bomPairMissedAfter
    measured += 1
  }

  return {
    before,
    after,
    removed: before === 0 ? null : Math.round(((before - after) / before) * 100),
    compliance: applied === 0 ? null : Math.round((met / applied) * 100),
    measured,
  }
}
