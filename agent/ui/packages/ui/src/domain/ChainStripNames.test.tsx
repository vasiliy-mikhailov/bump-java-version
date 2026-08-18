import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ChainStage } from '@bjv/types'
import { ChainStrip } from './ChainStrip'

/**
 * A TRIPLET MEMBER SHOULD NOT SAY ITS STAGE TWICE.
 *
 * The box carries the stage in small caps, so `survey-planner` inside a box labelled SURVEY spends
 * most of its width repeating the word directly above it, and across fourteen stages the strip
 * becomes a column of prefixes with the distinguishing word squeezed off the end.
 */
const triplet = (title: string, spoke = 1): ChainStage => ({
  title,
  within: '',
  steps: [
    { name: `${title}-planner`, role: 'planner', agent: true, spoke },
    { name: `${title}-doer`, role: 'doer', agent: true, spoke },
    { name: `${title}-verifier`, role: 'verifier', agent: true, spoke },
  ],
})

describe('ChainStrip step names', () => {
  it('writes plan, do and verify rather than the stage three times', () => {
    render(<ChainStrip stages={[triplet('survey')]} />)

    expect(screen.getByText('plan')).toBeTruthy()
    expect(screen.getByText('do')).toBeTruthy()
    expect(screen.getByText('verify')).toBeTruthy()
    expect(screen.queryByText('survey-planner')).toBeNull()
  })

  it('still says the stage once, above them', () => {
    render(<ChainStrip stages={[triplet('after-pins')]} />)

    expect(screen.getByText('after-pins')).toBeTruthy()
  })

  it('keeps the full agent name reachable, because that is what the trace is keyed on', () => {
    const { container } = render(<ChainStrip stages={[triplet('bump')]} />)

    const titles = [...container.querySelectorAll('[title]')].map((e) => e.getAttribute('title'))
    expect(titles).toContain('bump-planner')
    expect(titles).toContain('bump-verifier')
  })

  it('leaves a deterministic step its own name, which is all the information it has', () => {
    // "baseline" and "the three passes" are not triplet members. Shortening them to "do" would
    // replace the only word that means anything with one that means nothing.
    const stage: ChainStage = {
      title: 'baseline',
      within: '',
      steps: [{ name: 'the three passes', role: 'doer', agent: false, spoke: 0 }],
    }

    render(<ChainStrip stages={[stage]} />)

    expect(screen.getByText('the three passes')).toBeTruthy()
  })

  it('counts a repeat beside the short name, not instead of it', () => {
    render(<ChainStrip stages={[triplet('module-filter', 2)]} />)

    expect(screen.getByText('do')).toBeTruthy()
    expect(screen.getAllByText('2').length).toBeGreaterThan(0)
  })
})
