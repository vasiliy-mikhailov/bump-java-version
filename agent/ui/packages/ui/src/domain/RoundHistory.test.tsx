import type { RoundBoundary } from '@bjv/types'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RoundHistory } from './RoundHistory'

/**
 * THE ONE THING THE ROUND NUMBER CANNOT SAY.
 *
 * A lane has a wall-clock budget, and when it runs out the bump keeps its checkout and comes back
 * with its round one higher. That only holds while the pipeline is unchanged: deploy under a
 * waiting bump and the next lane starts the work over, and the count starts over with it. So a
 * repository picked up five times and never once continued reads as round one on its row, which is
 * exactly the repository somebody needs to find, and this list is where it stops being invisible.
 */

const AT = 1_700_000_000_000

function boundary(over: Partial<RoundBoundary> = {}): RoundBoundary {
  return {
    at: AT,
    state: 'paused',
    round: '1',
    because: 'the lane ended between stages, at the module walk',
    commit: 'ff7a4ab3',
    image: 'sha256:6f2c1b0a9d3',
    prompts: '54906737',
    boms: 'bb42094f',
    ...over,
  }
}

describe('the rounds a bump has already ended', () => {
  it('says nothing at all when no round has ever ended, which is nearly every bump', () => {
    const { container } = render(<RoundHistory rounds={[]} now={AT} />)

    expect(container.textContent).toBe('')
  })

  it('lists the rounds in the order they happened', () => {
    const { container } = render(
      <RoundHistory
        rounds={[boundary(), boundary({ round: '2', at: AT + 1000 })]}
        now={AT + 2000}
      />,
    )

    expect(container.textContent).toContain('round 1')
    expect(container.textContent).toContain('round 2')
    expect(container.textContent).not.toContain('started over')
  })

  it('names the deploy that made a round start over rather than continue', () => {
    const { container } = render(
      <RoundHistory
        rounds={[boundary(), boundary({ round: '1', at: AT + 1000, prompts: 'c3d4e5f6' })]}
        now={AT + 2000}
      />,
    )

    // TWO ROUNDS BOTH NUMBERED ONE IS THE SYMPTOM, and on its own it reads as a coincidence.
    expect(container.textContent).toContain('started over: the pipeline changed')
  })

  it('calls the last one stopped rather than a round when the harness gave up on it', () => {
    const { container } = render(
      <RoundHistory rounds={[boundary({ state: 'out-of-rounds', round: '4' })]} now={AT} />,
    )

    expect(container.textContent).toContain('stopped')
    expect(container.textContent).not.toContain('round 4')
  })
})
