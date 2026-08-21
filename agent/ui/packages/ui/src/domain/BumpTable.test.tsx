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
  round: null,
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

  it('hollows the gate of the one settlement the baseline stage throws itself, because a bump that never had a baseline never had anything to gate', () => {
    const [, gate] = lamps({ verdict: 'no-baseline', baselineGreen: false, gateGreen: false })
    expect(hollow(gate)).toBe(true)
    expect(gate.label).toBe('there was no baseline to gate against, so the gate never ran')
  })

  it('dims rather than hollows the gate of a bump settled as infrastructure, because the word comes from the arguer and the arguer runs only after a gate that ran and was not green', () => {
    const [, gate] = lamps({ verdict: 'infra', baselineGreen: false, gateGreen: false })
    expect(dim(gate)).toBe(true)
    expect(gate.label).toBe(
      'the gate never went green, and what settled this bump was the harness failing rather than the project',
    )
  })

  it('dims rather than hollows the gate that found no baseline to hold the project to, because that verdict is the gate’s own and it had already built and tested under the target', () => {
    const [, gate] = lamps({ verdict: 'NO_BASELINE_NOTESTS', baselineGreen: false, gateGreen: false })
    expect(dim(gate)).toBe(true)
    expect(gate.label).toBe(
      'the gate ran under JDK 21 and had no baseline set to hold the project to, so there was nothing to conserve',
    )
  })

  it('says of a baseline that ran and was not all green that the tests already red are not in the conserved set, because that qualifies what the bump can be held to rather than failing it', () => {
    const [baseline] = lamps({ verdict: 'FAIL_build_post', baselineGreen: false, gateGreen: false })
    expect(dim(baseline)).toBe(true)
    expect(baseline.label).toContain('not in the set this bump has to conserve')
  })

  it('never tells a no-baseline row that every test passed, because the record holds one that sent a green baseline for a suite in which nothing ran at all', () => {
    const [green] = lamps({ verdict: 'no-baseline', baselineGreen: true, gateGreen: false })
    expect(green.label).toBe(
      'nothing failed under JDK 17, and nothing passed either, so there is no set of tests for this bump to be held to',
    )
    const [red] = lamps({ verdict: 'no-baseline', baselineGreen: false, gateGreen: false })
    expect(red.label).toBe(
      'the project could not be built or tested under its own JDK 17, so no baseline was ever taken',
    )
  })

  it('lights the baseline lamp exactly when the wire says the baseline was green, because the lamp is the fact the server sent and not a second opinion about it', () => {
    for (const verdict of [
      'PASS',
      'FAIL_test_conservation',
      'no-baseline',
      'infra',
      'blocked-dependency',
    ] as Verdict[]) {
      expect(lit(lamps({ verdict, baselineGreen: true })[0])).toBe(true)
      expect(lit(lamps({ verdict, baselineGreen: false })[0])).toBe(false)
    }
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
    // AND THE ROUND SITS BESIDE `took`, WHICH IS THE COLUMN IT QUALIFIES. Four hours over one
    // round and four hours over three are different facts about a repository, and neither number
    // says the other.
    expect(heads.indexOf('round')).toBe(heads.indexOf('took') + 1)
    const green = container.querySelectorAll('th')[heads.indexOf('green')]
    expect(green?.getAttribute('title')).toContain(
      'the baseline before the bump, then the gate after it',
    )
  })

  /**
   * THE ROUND SHOWS UP EXACTLY WHEN IT MEANS SOMETHING.
   *
   * Six bumps in seven finish inside their first lane budget, so a column printing "1" on nearly
   * every row is a column a reader learns to skip past, and the row worth finding -- the repository
   * that has held a lane three times without settling -- would be hidden inside it.
   */
  it('shows the round only on a bump that has taken more than one', () => {
    const cellsFor = (round: string | null) => {
      const { container } = render(
        <BumpTable bumps={[{ ...ROW, round }]} hrefFor={(slug) => `/bump/${slug}`} now={ROW.at} />,
      )
      const heads = Array.from(container.querySelectorAll('th')).map((th) => th.textContent)
      return container.querySelectorAll('td')[heads.indexOf('round')]?.textContent
    }

    expect(cellsFor(null)).toBe('')
    expect(cellsFor('1')).toBe('')
    expect(cellsFor('3')).toBe('3')
  })

  /**
   * A PAUSED BUMP HAS NOT BEEN JUDGED, so its gate lamp says "never reached" rather than "ran and
   * was not green". The bump stopped between two stages and the gate may not have had its turn.
   */
  it('draws the gate lamp as unreached while a bump is between rounds', () => {
    for (const verdict of ['paused', 'out-of-rounds'] as Verdict[]) {
      const [, gate] = lamps({ verdict, gateGreen: false })
      expect(hollow(gate)).toBe(true)
      expect(gate.label).toContain('before the gate reached a verdict')
    }
  })
})
