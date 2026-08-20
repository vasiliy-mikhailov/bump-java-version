import type { TraceEvent } from '@bjv/types'
import { EmptyNote } from 'ratchet-ui/components'
import { TextFold } from '../primitives/TextFold'

export type EventFeedProps = { events: TraceEvent[] }

/**
 * WHAT HAPPENED, IN ORDER, WITH NOTHING CUT.
 *
 * The Java truncated bodies before sending them, and the evidence for why a bump failed was
 * routinely inside what was cut. The whole text travels now and the page folds it, which is a
 * decision the reader can undo.
 */
export function EventFeed({ events }: EventFeedProps) {
  if (events.length === 0) {
    return <EmptyNote>Nothing recorded for this filter.</EmptyNote>
  }
  return (
    <ol style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {events.map((e, i) => (
        <li
          key={`${e.at}-${i}`}
          style={{
            padding: '9px 0',
            borderTop: i === 0 ? 'none' : '1px solid var(--border-soft)',
          }}
        >
          <div style={{ display: 'flex', gap: '8px', alignItems: 'baseline', flexWrap: 'wrap' }}>
            <span
              style={{
                fontSize: '10px',
                textTransform: 'uppercase',
                letterSpacing: '0.05em',
                // THE WIRE READS DIFFERENTLY FROM THE ACCOUNT. Every other kind is the harness
                // reporting what it chose to; `exchange` is a listener under all of it recording
                // what actually went to the model, errors included. Worth telling apart at a
                // glance, because a run where those two disagree is the interesting case.
                color: e.kind === 'exchange' ? 'var(--role-verifier)' : 'var(--text-tertiary)',
              }}
            >
              {e.kind}
            </span>
            {e.agent === null ? null : (
              <span style={{ fontSize: '12px', color: 'var(--role-doer)' }}>{e.agent}</span>
            )}
            {e.stage === null ? null : (
              <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{e.stage}</span>
            )}
            {e.tool === null ? null : (
              <span
                style={{
                  fontSize: '11.5px',
                  fontFamily: 'ui-monospace, Menlo, monospace',
                  color: 'var(--text-tertiary)',
                }}
              >
                {e.tool}
              </span>
            )}
          </div>
          <div style={{ marginTop: '4px' }}>
            <TextFold text={e.text} />
          </div>
        </li>
      ))}
    </ol>
  )
}
