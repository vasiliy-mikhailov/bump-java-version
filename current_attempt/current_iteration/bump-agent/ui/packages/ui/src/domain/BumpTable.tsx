import type { BumpSummary } from '@bjv/types'
import { EmptyNote } from '../primitives/EmptyNote'
import { RelativeTime } from '../primitives/RelativeTime'
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
            <th style={{ ...th, textAlign: 'right' }}>running for</th>
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
                  <span>{elapsed(b.startedAt, b.at === 0 ? now : Math.max(b.at, now))}</span>
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
 * A DURATION, NOT A TIME AGO. `relative` says "3h ago"; this says "3h", which is what a reader wants
 * of a bump that is still going and has no "ago" about it yet.
 */
function elapsed(from: number, to: number): string {
  const s = Math.max(0, Math.round((to - from) / 1000))
  if (s < 60) return `${s}s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  return m % 60 === 0 ? `${h}h` : `${h}h ${m % 60}m`
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
