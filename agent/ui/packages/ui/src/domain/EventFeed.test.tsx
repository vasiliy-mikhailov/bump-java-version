import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { TraceEvent } from '@bjv/types'
import { EventFeed } from './EventFeed'

/**
 * THE THREE LABELS THAT WERE ALWAYS THERE AND USUALLY EMPTY.
 *
 * This server emits every field on every trace line and writes JSON `null` where there is nothing
 * to say, because a deterministic step has no agent and a line that called no tool has no tool. The
 * type declared those three fields optional, which says the KEY may be missing, and the feed asked
 * `=== undefined`. A null is not undefined, so the test failed and the span was rendered anyway,
 * with nothing inside it.
 *
 * An empty span in a flex row is not nothing: it is a flex item, and the row's 8px gap is drawn
 * on both sides of it. Every line of every record carried up to three of them, which is the same
 * bug this codebase already documents shipping once, when a running bump drew a bare arrow in the
 * CVE column.
 *
 * The validators in `ratchet-ui` are what found it. Their author had already written the rule down
 * in prose: null and absent are different, and a page cannot tell them apart after `JSON.parse`
 * unless something checked.
 */
describe('the record of what happened', () => {
  const line = (over: Partial<TraceEvent> = {}): TraceEvent => ({
    at: 1_787_200_000_000,
    kind: 'built',
    agent: null,
    stage: null,
    tool: null,
    text: 'mvn -B -o package',
    inTokens: 0,
    outTokens: 0,
    ms: 0,
    ...over,
  })

  /** Every label on a line, empty ones included, which is the whole point of asking this way. */
  const labels = (container: HTMLElement) =>
    Array.from(container.querySelectorAll('li > div > span')).map((span) => span.textContent)

  /** The kind is the only label a deterministic line has, so it should be the only one drawn. */
  it('draws one label on a line that names no agent, no stage and no tool', () => {
    const { container } = render(<EventFeed events={[line()]} />)

    expect(labels(container)).toEqual(['built'])
  })

  it('draws every label the server did send, in the order the eye reads them', () => {
    const spoken = line({ kind: 'tool', agent: 'survey-doer', stage: 'survey', tool: 'read' })

    const { container } = render(<EventFeed events={[spoken]} />)

    expect(labels(container)).toEqual(['tool', 'survey-doer', 'survey', 'read'])
  })

  it('still says nothing was recorded when there is nothing to draw', () => {
    render(<EventFeed events={[]} />)

    expect(screen.getByText('Nothing recorded for this filter.')).toBeTruthy()
  })
})
