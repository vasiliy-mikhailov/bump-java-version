import { describe, expect, it } from 'vitest'
import type { BumpSummary } from '@bjv/types'
import { bomTotals } from './boms'

function bump(part: Partial<BumpSummary>): BumpSummary {
  return {
    bomMet: null, bomMissed: null, bomMetBefore: null, bomMissedBefore: null,
    bomPairApplied: null, bomPairMissedBefore: null, bomPairMissedAfter: null,
    ...part,
  } as unknown as BumpSummary
}

describe('bomTotals', () => {
  it('rates the movement over the comparable pair and compliance over everything measurable', () => {
    const t = bomTotals([
      bump({ bomMet: 5, bomMissed: 0, bomPairApplied: 5, bomPairMissedBefore: 4, bomPairMissedAfter: 0 }),
      bump({ bomMet: 4, bomMissed: 1, bomPairApplied: 5, bomPairMissedBefore: 3, bomPairMissedAfter: 1 }),
    ])

    expect(t.before).toBe(7)
    expect(t.after).toBe(1)
    expect(t.removed).toBe(86)
    expect(t.compliance).toBe(90)
    expect(t.measured).toBe(2)
  })

  it('counts a bump toward compliance even when its movement is not comparable', () => {
    // THE TWO DENOMINATORS ARE DIFFERENT ON PURPOSE. A bump that never reached a green gate has a
    // resolved tree before and none after, so it can say where it stands and cannot say what it
    // moved. Dropping it from compliance too would throw away the half it can answer.
    const t = bomTotals([bump({ bomMet: 3, bomMissed: 1 })])

    expect(t.compliance).toBe(75)
    expect(t.measured).toBe(0)
    expect(t.removed).toBeNull()
  })

  it('does not read a missing after-scan as an issue that went away', () => {
    // The failure this guards. Raw totals said "was 3 of 11, now 0 of 4" on real repositories,
    // where the four is all that was measured rather than all that was left.
    const t = bomTotals([
      bump({ bomMet: 0, bomMissed: 4, bomMetBefore: 3, bomMissedBefore: 8, bomPairApplied: 0 }),
    ])

    expect(t.before).toBe(0)
    expect(t.after).toBe(0)
    expect(t.removed).toBeNull()
    expect(t.measured).toBe(0)
  })

  it('has no rate rather than nought when there was nothing to remove', () => {
    const t = bomTotals([
      bump({ bomMet: 3, bomMissed: 0, bomPairApplied: 3, bomPairMissedBefore: 0, bomPairMissedAfter: 0 }),
    ])

    expect(t.removed).toBeNull()
    expect(t.compliance).toBe(100)
  })

  it('shows a bump that made things worse as a negative rate', () => {
    const t = bomTotals([
      bump({ bomMet: 1, bomMissed: 3, bomPairApplied: 4, bomPairMissedBefore: 1, bomPairMissedAfter: 3 }),
    ])

    expect(t.removed).toBe(-200)
    expect(t.compliance).toBe(25)
  })

  it('is empty rather than wrong when nothing was measured', () => {
    const t = bomTotals([])

    expect(t.measured).toBe(0)
    expect(t.removed).toBeNull()
    expect(t.compliance).toBeNull()
  })
})
