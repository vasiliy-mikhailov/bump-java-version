import { describe, expect, it } from 'vitest'
import type { Package } from '@bjv/types'
import { best, collapse } from './PackageTable'

const pkg = (name: string, cvesBefore: number, cvesAfter: number | null): Package => ({
  name,
  module: '.',
  versionBefore: '1',
  versionAfter: cvesAfter === null ? null : '2',
  cvesBefore,
  cvesAfter,
})

const order = (ps: Package[]) => collapse(ps).map((r) => r.name)

/**
 * THE TABLE IS ORDERED BY WHAT THE BUMP DID, NOT BY HOW BAD THE PROJECT USED TO BE.
 *
 * It sorted on CVEs BEFORE, which on a successful bump ranks rows by a number the bump has already
 * made obsolete, and puts the biggest win and the biggest remaining problem in the same place.
 */
describe('the dependency drill-down order', () => {
  it('leads with what cleared the most', () => {
    expect(order([pkg('small', 4, 0), pkg('tomcat', 19, 0), pkg('middling', 11, 2)])).toEqual([
      'tomcat', // cleared 19
      'middling', // cleared 9
      'small', // cleared 4
    ])
  })

  it('keeps what is still broken above what was never a problem', () => {
    // Both cleared nothing, so the old tiebreak would have sorted them alphabetically and buried
    // eleven live CVEs under a hundred clean dependencies.
    expect(order([pkg('aaa-clean', 0, 0), pkg('zzz-stuck', 11, 11)])).toEqual([
      'zzz-stuck',
      'aaa-clean',
    ])
  })

  it('sinks anything the bump made worse below everything it did not', () => {
    expect(order([pkg('clean', 0, 0), pkg('worse', 10, 15), pkg('fixed', 5, 0)])).toEqual([
      'fixed', // cleared 5
      'clean', // cleared 0
      'worse', // cleared -5
    ])
  })

  it('scores an unmeasured after as having cleared nothing, not everything', () => {
    // cvesAfter null means no after scan saw it. Treating that as 0 would score it as a clean
    // sweep and float it to the top of the table above every real fix.
    expect(order([pkg('unmeasured', 20, null), pkg('genuinely-fixed', 3, 0)])).toEqual([
      'genuinely-fixed',
      'unmeasured',
    ])
  })

  it('is stable, so two renders of the same data agree', () => {
    const rows = [pkg('b', 2, 1), pkg('a', 2, 1), pkg('c', 2, 1)]
    expect(order(rows)).toEqual(['a', 'b', 'c'])
    expect(order([...rows].reverse())).toEqual(['a', 'b', 'c'])
  })

  it('compares two rows directly the same way', () => {
    expect(best({ ...pkg('x', 9, 0), key: 'x', modules: 1 },
                { ...pkg('y', 1, 0), key: 'y', modules: 1 })).toBeLessThan(0)
  })
})
