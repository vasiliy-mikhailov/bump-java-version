import { describe, expect, it } from 'vitest'
import type { BumpSummary } from '@bjv/types'
import { cveTotals } from './cves'

const bump = (cvesBefore: number | null, cvesAfter: number | null): BumpSummary =>
  ({ slug: `${cvesBefore}-${cvesAfter}`, cvesBefore, cvesAfter }) as BumpSummary

describe('cveTotals', () => {
  it('adds up the bumps that have both numbers', () => {
    const t = cveTotals([bump(63, 2), bump(24, 1), bump(35, 35)])

    expect(t.before).toBe(122)
    expect(t.after).toBe(38)
    expect(t.removed).toBe(84)
    expect(t.measured).toBe(3)
    expect(t.rate).toBe(69)
  })

  it('ignores a bump with a before and no after, rather than counting it as cleared', () => {
    // THE FAILURE MODE THIS EXISTS TO PREVENT. The after scan runs only on a green gate, so a
    // failed bump has a before and no after. Summing the columns independently would add 337 to
    // "before" and nothing to "after" and report the run as having cleared all 337.
    const t = cveTotals([bump(63, 2), bump(337, null)])

    expect(t.before).toBe(63)
    expect(t.after).toBe(2)
    expect(t.measured).toBe(1)
    expect(t.rate).toBe(97)
  })

  it('ignores a bump with neither, which is most of a fresh sweep', () => {
    const t = cveTotals([bump(null, null), bump(null, null)])

    expect(t).toEqual({ before: 0, after: 0, removed: 0, rate: null, measured: 0 })
  })

  it('has no rate when nothing was vulnerable to begin with', () => {
    // Zero before is not zero per cent cleared: there was nothing to clear, and 0/0 rendered as
    // "0%" reads as a run that achieved nothing.
    const t = cveTotals([bump(0, 0)])

    expect(t.rate).toBeNull()
    expect(t.measured).toBe(1)
  })

  it('reports a negative removal rather than hiding it', () => {
    // A bump can introduce vulnerabilities: a framework raised past a CVE-free release, a new
    // transitive arriving with the upgrade. Clamping that to zero would make the dashboard
    // incapable of showing the one outcome worth reacting to.
    const t = cveTotals([bump(10, 15)])

    expect(t.removed).toBe(-5)
    expect(t.rate).toBe(-50)
  })

  it('counts nothing as nothing', () => {
    expect(cveTotals([])).toEqual({ before: 0, after: 0, removed: 0, rate: null, measured: 0 })
  })
})
