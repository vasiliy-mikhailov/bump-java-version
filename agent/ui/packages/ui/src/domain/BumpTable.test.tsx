import type { BumpSummary, Verdict } from '@bjv/types'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BumpTable } from './BumpTable'

/**
 * THE TWO FACTS THE SERVER HAS ALWAYS SENT AND NO READER COULD SEE.
 *
 * `baselineGreen` and `gateGreen` have been on every row since they were added, computed by the
 * harness and serialised on the wire, and until now the only thing in this repository that read
 * either was a test fixture. The lamps are how they reach a reader, and what these tests guard is
 * the distinction the lamps exist for: a gate that ran and never went green and a gate that was
 * never reached are different answers, and a component that could only be on or off would say they
 * were the same.
 *
 * THE SENTENCES ARE ASSERTED AND NOT ONLY THE APPEARANCE. They are the accessible name of an
 * element with no text in it, so they are the whole of what a reader without a mouse gets, and they
 * are this dashboard's own vocabulary rather than the shared component's. `Lamp` came from the
 * sibling tool and takes the colour and the whole sentence as props precisely so that no pipeline's
 * meaning of green travels with it.
 */

const ROW: BumpSummary = {
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

type Lamp = { style: string; label: string }

/** The two lamps of a single row, left then right, as they reach the page. */
function lamps(over: Partial<BumpSummary>): [Lamp, Lamp] {
  const { container } = render(
    <BumpTable bumps={[{ ...ROW, ...over }]} hrefFor={(slug) => `/bump/${slug}`} now={ROW.at} />,
  )
  const found = Array.from(container.querySelectorAll('[role="img"]')).map((node) => ({
    // Whitespace out, because whether a serialiser puts a space after a colon is not the subject.
    style: (node.getAttribute('style') ?? '').replace(/\s+/g, ''),
    label: node.getAttribute('aria-label') ?? '',
  }))
  expect(found).toHaveLength(2)
  return [found[0] as Lamp, found[1] as Lamp]
}

const lit = (lamp: Lamp): boolean => lamp.style.includes('box-shadow')
const hollow = (lamp: Lamp): boolean => lamp.style.includes('border-style:dashed')
const dim = (lamp: Lamp): boolean => !lit(lamp) && !hollow(lamp)

describe('the two lamps beside the verdict', () => {
  it('lights both for a bump whose baseline was green and whose gate went green, since that is the pair the verdict word compresses', () => {
    const [baseline, gate] = lamps({})
    expect(lit(baseline)).toBe(true)
    expect(lit(gate)).toBe(true)
    expect(baseline.label).toContain('every test passed under JDK 17 before anything was changed')
    expect(gate.label).toContain('it built under JDK 21 and kept every test the baseline was holding')
  })

  it('dims the gate that ran and never went green rather than hollowing it, because it did run and what it said is what settled the bump', () => {
    const [baseline, gate] = lamps({ verdict: 'FAIL_test_conservation', gateGreen: false })
    expect(lit(baseline)).toBe(true)
    expect(dim(gate)).toBe(true)
    expect(gate.label).toBe('the gate ran and never went green, which is what settled this bump')
  })

  it('hollows the gate of a bump that is still running, because saying it ran and failed would invent the half of the answer that has not arrived', () => {
    const [baseline, gate] = lamps({ verdict: 'bumping', gateGreen: false })
    expect(lit(baseline)).toBe(true)
    expect(hollow(gate)).toBe(true)
    expect(gate.label).toBe('the gate has not spoken for this bump yet')
  })

  it('hollows both lamps of a queued row, since nothing has run and no baseline has been taken', () => {
    const [baseline, gate] = lamps({ verdict: 'queued', baselineGreen: false, gateGreen: false })
    expect(hollow(baseline)).toBe(true)
    expect(hollow(gate)).toBe(true)
    expect(baseline.label).toBe('nothing has run yet, so no baseline has been taken')
  })

  it('hollows the gate of every settlement thrown before there was anything to gate, because none of the three has a gate behind it', () => {
    for (const [verdict, says] of [
      ['no-baseline', 'there was no baseline to gate against, so the gate never ran'],
      ['NO_BASELINE_NOTESTS', 'there was no baseline to gate against, so the gate never ran'],
      ['infra', 'the harness failed before the gate could run'],
    ] as [Verdict, string][]) {
      const [, gate] = lamps({ verdict, baselineGreen: false, gateGreen: false })
      expect(hollow(gate)).toBe(true)
      expect(gate.label).toBe(says)
    }
  })

  it('says of a baseline that ran and was not all green that the tests already red are not in the conserved set, because that qualifies what the bump can be held to rather than failing it', () => {
    const [baseline] = lamps({ verdict: 'FAIL_build_post', baselineGreen: false, gateGreen: false })
    expect(dim(baseline)).toBe(true)
    expect(baseline.label).toContain('not in the set this bump has to conserve')
  })

  it('draws both lamps in this dashboard’s own colour and takes none from the shared package, so that no other pipeline’s meaning of green travels with them', () => {
    const [baseline, gate] = lamps({})
    for (const lamp of [baseline, gate]) {
      expect(lamp.style).toContain('var(--state-pass)')
      expect(lamp.style).not.toContain('build-red')
      expect(lamp.style).not.toContain('build-green')
    }
  })

  it('explains the notation once on the heading rather than on every row, the way this table already does for the pipeline column', () => {
    const { container } = render(
      <BumpTable bumps={[ROW]} hrefFor={(slug) => `/bump/${slug}`} now={ROW.at} />,
    )
    const heads = Array.from(container.querySelectorAll('th')).map((th) => th.textContent)
    // Straight after the verdict, so the two facts sit beside the word they support.
    expect(heads.indexOf('green')).toBe(heads.indexOf('verdict') + 1)
    expect(heads.indexOf('tests')).toBe(heads.indexOf('green') + 1)
    const green = container.querySelectorAll('th')[heads.indexOf('green')]
    expect(green?.getAttribute('title')).toContain(
      'the baseline before the bump, then the gate after it',
    )
  })
})
