import type { RoundBoundary } from '@bjv/types'
import { Card } from '../primitives/Card'
import { RelativeTime } from '../primitives/RelativeTime'

export type RoundHistoryProps = { rounds: RoundBoundary[]; now?: number }

/**
 * EVERY ROUND THIS BUMP HAS ENDED, BECAUSE THE NUMBER ON ITS OWN LIES IN THE CASE THAT MATTERS.
 *
 * A lane has a wall-clock budget. When it runs out the bump stops between two stages, keeps its
 * checkout and its journal, and goes back to the queue with its round one higher; the next lane
 * continues from there while the pipeline is unchanged. When the pipeline HAS changed the run has
 * to start over, and the count goes back to one with it.
 *
 * So a repository that has been picked up five times and never once continued reads as round one,
 * which is exactly the repository somebody should look at: deploying is starving it. That is
 * invisible in a single number and obvious in a list, and it is the reason this component exists
 * rather than a bigger digit on the row above.
 *
 * TWO CONSECUTIVE BOUNDARIES WITH DIFFERENT FINGERPRINTS ARE THE DIAGNOSIS. The four fields are
 * already on every settlement row for a different reason, so nothing new is recorded to say it.
 */
export function RoundHistory({ rounds, now = Date.now() }: RoundHistoryProps) {
  if (rounds.length === 0) {
    return null
  }
  return (
    <Card>
      <ol style={{ margin: 0, padding: 0, listStyle: 'none', fontSize: '12.5px' }}>
        {rounds.map((r, i) => {
          const before = i === 0 ? null : rounds[i - 1]
          const startedOver = before !== undefined && before !== null && !sameStamp(before, r)
          return (
            <li
              key={`${r.at}-${i}`}
              style={{
                padding: '7px 0',
                borderTop: i === 0 ? 'none' : '1px solid var(--border-subtle)',
                color: 'var(--text-secondary)',
              }}
            >
              <span style={{ color: 'var(--text-primary)' }}>
                {r.state === 'out-of-rounds' ? 'stopped' : `round ${r.round ?? '?'}`}
              </span>
              {' · '}
              <RelativeTime at={r.at} now={now} />
              {startedOver ? (
                <span
                  style={{ color: 'var(--verdict-again)' }}
                  title="the harness was deployed while this bump was waiting, so its stored state belonged to a pipeline that is no longer running and the run began again"
                >
                  {' · started over: the pipeline changed'}
                </span>
              ) : null}
              <div style={{ color: 'var(--text-tertiary)', whiteSpace: 'pre-wrap' }}>
                {r.because ?? ''}
              </div>
            </li>
          )
        })}
      </ol>
    </Card>
  )
}

/** The four fields, compared the way the resume decision compares them: all of them, or none. */
function sameStamp(a: RoundBoundary, b: RoundBoundary): boolean {
  return (
    a.commit === b.commit &&
    a.image === b.image &&
    a.prompts === b.prompts &&
    a.boms === b.boms
  )
}
