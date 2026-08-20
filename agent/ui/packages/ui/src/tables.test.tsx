import { render } from '@testing-library/react'
import type { BumpSummary, Package } from '@bjv/types'
import { describe, expect, it } from 'vitest'
import { BumpTable } from './domain/BumpTable'
import { PackageTable } from './domain/PackageTable'
import { CELL, HEAD } from './primitives/table'

/**
 * TWO TABLES A CLICK APART HAVE TO BE SET THE SAME.
 *
 * The corpus and a bump's dependencies were one pixel apart vertically, because each carried its
 * own copy of `th` and `td` and one of them had been adjusted. Nothing about the data justified it,
 * and a reader who scrolls from one to the other does not consciously notice a pixel — they notice
 * that the second page feels like a different page.
 *
 * This is the guard on that, and it is deliberately not an assertion about the number 9. It asserts
 * that whatever the number is, every cell on the site agrees about it: a future decision to set
 * tables tighter is one edit in `table.ts`, and a future drift is one failing test here.
 */

const BUMP: BumpSummary = {
  slug: 'rr_17_57',
  repo: 'owner/thing',
  sha: 'bdc86ebe64e2',
  from: 17,
  to: 21,
  verdict: 'PASS',
  because: null,
  baselineGreen: true,
  gateGreen: true,
  preTests: 412,
  cvesBefore: 77,
  cvesAfter: 12,
  startedAt: 1_700_000_000_000,
  at: 1_700_000_900_000,
  events: 1834,
  humanMinutes: 240,
  bomMet: 7,
  bomMissed: 2,
  bomMetBefore: 3,
  bomMissedBefore: 6,
  bomPairApplied: 9,
  bomPairMissedBefore: 6,
  bomPairMissedAfter: 2,
  bomOutstanding: null,
  commit: null,
  image: null,
  prompts: null,
  boms: null,
}

const PACKAGE: Package = {
  name: 'org.apache.tomcat.embed:tomcat-embed-core',
  module: 'core',
  versionBefore: '9.0.60',
  versionAfter: '10.1.55',
  cvesBefore: 17,
  cvesAfter: 0,
}

/** Every inset used by every cell of a rendered table, headings included. */
function insets(root: HTMLElement): Set<string> {
  const cells = Array.from(root.querySelectorAll<HTMLElement>('th, td'))
  return new Set(cells.map((cell) => cell.style.padding))
}

describe('the tables on this site', () => {
  it('sets a cell the same way in the corpus and in a bump’s dependencies', () => {
    const corpus = render(<BumpTable bumps={[BUMP]} hrefFor={(slug) => `/bump/?slug=${slug}`} />)
    const dependencies = render(<PackageTable packages={[PACKAGE]} />)

    expect(insets(corpus.container)).toEqual(insets(dependencies.container))
  })

  it('sets a heading and the body under it at the same inset', () => {
    // A header row set tighter than the rows it heads is a rule about nothing: the first row of a
    // table is not more cramped than the rest of it. It was, in two of the three tables.
    expect(HEAD.padding).toBe(CELL.padding)
  })

  it('holds every cell at the page gutter', () => {
    // 24px, the same inset the header, the tally strip and every section heading use. A column that
    // started somewhere else would not line up with the heading above it.
    const corpus = render(<BumpTable bumps={[BUMP]} hrefFor={(slug) => `/bump/?slug=${slug}`} />)

    for (const inset of insets(corpus.container)) {
      expect(inset.endsWith(' 24px')).toBe(true)
    }
  })
})
