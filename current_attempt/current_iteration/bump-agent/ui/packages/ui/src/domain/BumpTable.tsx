import type { BumpSummary } from '@bjv/types'
import { EmptyNote } from '../primitives/EmptyNote'
import { RelativeTime, duration, spellMinutes } from '../primitives/RelativeTime'
import { VerdictPill } from './VerdictPill'

export type BumpTableProps = {
  bumps: BumpSummary[]
  hrefFor: (slug: string) => string
  now?: number
}

/** The corpus, one row per bump. The `now` is taken once so fifty rows cannot disagree. */
export function BumpTable({ bumps, hrefFor, now = Date.now() }: BumpTableProps) {
  if (bumps.length === 0) {
    return <EmptyNote>No bumps yet. The sweep writes a row as soon as one starts.</EmptyNote>
  }
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' }}>
        <thead>
          <tr>
            <th style={th}>repository</th>
            <th style={th}>hop</th>
            <th style={th}>verdict</th>
            <th style={{ ...th, textAlign: 'right' }}>tests</th>
            <th style={{ ...th, textAlign: 'right' }}>CVEs</th>
            <th style={{ ...th, textAlign: 'right' }}>took</th>
            <th style={{ ...th, textAlign: 'right' }}>a person would have</th>
            <th style={{ ...th, textAlign: 'right' }}>last event</th>
          </tr>
        </thead>
        <tbody>
          {bumps.map((b) => (
            <tr key={b.slug} style={{ borderTop: '1px solid var(--border-soft)' }}>
              <td style={td}>
                <a
                  href={hrefFor(b.slug)}
                  style={{ color: 'var(--text-primary)', textDecoration: 'none' }}
                >
                  {b.repo}
                </a>
              </td>
              <td style={{ ...td, color: 'var(--text-tertiary)' }}>
                {b.from} → {b.to}
              </td>
              <td style={td}>
                <VerdictPill verdict={b.verdict} href={hrefFor(b.slug)} />
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                {b.preTests == null ? (
                  '—'
                ) : (
                  <>
                    {b.preTests}
                    {b.lost !== undefined && b.lost > 0 ? (
                      <span style={{ color: 'var(--cve-introduced)' }}> −{b.lost}</span>
                    ) : null}
                  </>
                )}
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                {b.cvesBefore == null ? (
                  '—'
                ) : (
                  <>
                    {b.cvesBefore}
                    {b.cvesAfter == null ? null : (
                      <>
                        {' → '}
                        <span
                          style={{
                            color:
                              b.cvesAfter < b.cvesBefore
                                ? 'var(--cve-cleared)'
                                : b.cvesAfter > b.cvesBefore
                                  ? 'var(--cve-introduced)'
                                  : 'var(--cve-remaining)',
                          }}
                        >
                          {b.cvesAfter}
                        </span>
                      </>
                    )}
                  </>
                )}
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                {/* HOW LONG IT HAS BEEN GOING. Beside the column on its right this is the whole
                    diagnosis: old and recently active is slow, old and silent is stuck. */}
                {b.startedAt === 0 ? (
                  <span style={{ color: 'var(--text-tertiary)' }}>—</span>
                ) : (
                  <>
                    <span>{duration(took(b, now))}</span>
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>
                      {b.events.toLocaleString()} event(s)
                    </div>
                  </>
                )}
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                {/* THE ESTIMATE, AND ONLY EVER ITS OWN COLUMN. It is what the estimator triad
                    priced the work that LANDED at, checked against the log by a verifier. Putting
                    it beside `took` is the whole point; adding it to anything measured would be
                    laundering a guess into a number. */}
                {b.humanMinutes == null ? (
                  <span style={{ color: 'var(--text-tertiary)' }}>—</span>
                ) : (
                  <span>{spellMinutes(b.humanMinutes)}</span>
                )}
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                {/* A queued row has no event yet, and "56 years ago" is worse than saying so. */}
                {b.at === 0 ? (
                  <span style={{ color: 'var(--text-tertiary)' }}>—</span>
                ) : (
                  <span style={{ color: stale(b, now) ? 'var(--verdict-again)' : undefined }}>
                    <RelativeTime at={b.at} now={now} />
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** The sibling's `th`: 11px, uppercase, letterspaced, on a strong rule. */
/**
 * HOW LONG THIS BUMP HAS COST SO FAR, which for a settled one is how long it took.
 *
 * A running bump is measured to now; a settled one to its last event, because a bump that finished
 * an hour ago did not spend that hour working. Reading the clock for both was the bug that made
 * every finished row keep growing.
 */
function took(b: BumpSummary, now: number): number {
  const running = b.verdict === 'bumping'
  const end = running || b.at === 0 ? now : b.at
  return Math.max(0, end - b.startedAt)
}

/**
 * A RUNNING BUMP THAT HAS NOT SPOKEN IN FIVE MINUTES IS WORTH LOOKING AT.
 *
 * One agent call can legitimately take minutes — a reasoning model on a large context does — so this
 * is not a failure, it is the threshold at which a reader should start reading rather than waiting.
 * Marked rather than hidden: the row still says what it says, in a colour that draws the eye.
 */
function stale(b: BumpSummary, now: number): boolean {
  return b.verdict === 'bumping' && b.at > 0 && now - b.at > 5 * 60_000
}

const th = {
  textAlign: 'left',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  padding: '9px 24px',
  borderBottom: '1px solid var(--border-strong)',
} as const
const td = { padding: '9px 24px', verticalAlign: 'top' } as const
