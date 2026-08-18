import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Package } from '@bjv/types'
import { PackageTable, collapse } from './PackageTable'

/**
 * THE ROWS THAT LOOKED LIKE A RENDERING BUG AND WERE NOT.
 *
 * The Java's table showed package, version and CVE count and NOT the module, so a six-module project
 * rendered `io.netty:netty-codec-http 4.1.79.Final` six identical times. They were six different
 * facts wearing the same label: the scan is per module. Measured across this corpus, 16 of 27
 * multi-module repositories resolve some package to more than one version, so the repetition is not
 * even always redundant.
 */
describe('PackageTable', () => {
  const pkg = (module: string, name: string, before: string, after: string): Package => ({
    module,
    name,
    versionBefore: before,
    versionAfter: after,
    cvesBefore: 6,
    cvesAfter: 4,
  })

  it('collapses identical rows and counts the modules instead of repeating them', () => {
    const six = ['a', 'b', 'c', 'd', 'e', 'f'].map((m) =>
      pkg(m, 'io.netty:netty-codec-http', '4.1.79.Final', '4.1.101.Final'),
    )

    const rows = collapse(six)

    expect(rows).toHaveLength(1)
    expect(rows[0]?.modules).toBe(6)
  })

  it('keeps rows apart when the modules genuinely disagree about the version', () => {
    // byte-buddy at three versions in one repository is a real row from this corpus, and collapsing
    // it would hide the split that makes a pin check per module necessary in the first place.
    const split = [
      pkg('a', 'net.bytebuddy:byte-buddy', '1.12.21', '1.14.12'),
      pkg('b', 'net.bytebuddy:byte-buddy', '1.14.6', '1.14.12'),
    ]

    expect(collapse(split)).toHaveLength(2)
  })

  it('shows the module count rather than six identical lines', () => {
    render(
      <PackageTable
        packages={['a', 'b', 'c'].map((m) => pkg(m, 'io.netty:netty-codec-http', '1', '2'))}
      />,
    )

    expect(screen.getByText('3 modules')).toBeDefined()
    expect(screen.getAllByText('io.netty:netty-codec-http')).toHaveLength(1)
  })

  it('names the single module when there is only one', () => {
    render(<PackageTable packages={[pkg('common', 'x:y', '1', '2')]} />)

    expect(screen.getByText('common')).toBeDefined()
  })

  it('says so when a version did not move, rather than repeating it', () => {
    render(<PackageTable packages={[pkg('a', 'x:y', '1.0', '1.0')]} />)

    expect(screen.getByText('unchanged')).toBeDefined()
  })

  it('states emptiness rather than rendering nothing', () => {
    // An empty table that renders as nothing is indistinguishable from one that failed to load, and
    // the reader's next move differs completely between the two.
    render(<PackageTable packages={[]} />)

    expect(screen.getByText(/No dependency scan/)).toBeDefined()
  })

  it('orders by what a reader came for: the most vulnerable first', () => {
    const rows = collapse([
      { ...pkg('a', 'low:one', '1', '2'), cvesBefore: 1, cvesAfter: 0 },
      { ...pkg('a', 'high:two', '1', '2'), cvesBefore: 22, cvesAfter: 19 },
    ])

    expect(rows[0]?.name).toBe('high:two')
  })
})
