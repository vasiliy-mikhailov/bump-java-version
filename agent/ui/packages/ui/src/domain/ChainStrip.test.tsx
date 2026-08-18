import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ChainStage } from '@bjv/types'
import { ChainStrip } from './ChainStrip'

/**
 * THE STRIP IS THE PAGE'S ANSWER TO "HOW FAR DID THIS GET", and it has been wrong twice.
 *
 * Once by listing a stage that had been deleted, because the strip was a hand-typed copy of a chain
 * declared elsewhere. Once by drawing every stage as a PAIR, so a reader could not tell which of the
 * two agents decided the approach and which judged it.
 */
describe('ChainStrip', () => {
  const stage = (title: string, within: string, spoke: number[]): ChainStage => ({
    title,
    within,
    steps: [
      { name: `${title}-planner`, role: 'planner', agent: true, spoke: spoke[0] ?? 0 },
      { name: `${title}-doer`, role: 'doer', agent: true, spoke: spoke[1] ?? 0 },
      { name: `${title}-verifier`, role: 'verifier', agent: true, spoke: spoke[2] ?? 0 },
    ],
  })

  it('draws all three roles of a stage, not two', () => {
    render(<ChainStrip stages={[stage('bump', '', [1, 1, 1])]} />)

    expect(screen.getByText('plan')).toBeDefined()
    expect(screen.getByText('do')).toBeDefined()
    expect(screen.getByText('verify')).toBeDefined()
    // The stage is said once, above them, rather than three times inside them.
    expect(screen.getByText('bump')).toBeDefined()
  })

  it('draws stages the bump never reached, dimmed rather than omitted', () => {
    // A strip that lists only who has spoken shows a chain of fourteen as a chain of two, and hides
    // where the run actually stopped.
    render(<ChainStrip stages={[stage('survey', '', [1, 1, 1]), stage('gate', '', [0, 0, 0])]} />)

    expect(screen.getByTitle('gate-doer')).toBeDefined()
  })

  it('marks a deterministic step differently from an agent', () => {
    const det: ChainStage = {
      title: 'baseline',
      within: '',
      steps: [{ name: 'baseline', role: 'doer', agent: false, spoke: 1 }],
    }
    render(<ChainStrip stages={[det]} />)

    // The reader's question is "can this change the workspace", and the gate deciding a bump is the
    // most trustworthy event on the page.
    expect(screen.getByTitle('deterministic')).toBeDefined()
  })

  it('says the verifier sent the work back when the doer ran more than once', () => {
    render(<ChainStrip stages={[stage('bump', '', [1, 3, 1])]} />)

    expect(screen.getByTitle('the verifier sent this back')).toBeDefined()
  })

  it('does not claim a loop when every step ran once', () => {
    render(<ChainStrip stages={[stage('bump', '', [1, 1, 1])]} />)

    expect(screen.queryByTitle('the verifier sent this back')).toBeNull()
  })

  it('nests a stage inside the stage that runs it', () => {
    render(
      <ChainStrip
        stages={[stage('modules', '', [1, 1, 1]), stage('before-pins', 'modules', [1, 1, 1])]}
      />,
    )

    // Rendered once, under its parent, rather than as a peer of it.
    expect(screen.getAllByTitle('before-pins-doer')).toHaveLength(1)
  })

  it('links only the steps a reader can actually filter to', () => {
    render(
      <ChainStrip
        stages={[stage('bump', '', [1, 0, 1])]}
        hrefFor={(a) => `/x?agent=${a}`}
        allHref="/x"
      />,
    )

    // A link that filters to nothing answers a click with an empty page.
    expect(screen.getByTitle('bump-planner').closest('a')).not.toBeNull()
    expect(screen.getByTitle('bump-doer').closest('a')).toBeNull()
  })

  it('is read-only when no link builder is given', () => {
    render(<ChainStrip stages={[stage('bump', '', [1, 1, 1])]} />)

    expect(screen.getByTitle('bump-doer').closest('a')).toBeNull()
  })
})
