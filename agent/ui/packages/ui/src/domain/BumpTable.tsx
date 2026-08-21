import type { BumpSummary } from '@bjv/types'
import { DataTable, EmptyNote, HumanCost, TimeSpent, type Column } from 'ratchet-ui/components'
import { RelativeTime } from '../primitives/RelativeTime'
import { PipelineMark } from './PipelineMark'
import type { StampedBump } from './pipeline'
import { VerdictPill } from './VerdictPill'

export type BumpTableProps = {
  /**
   * The stamp fields are optional on this type rather than required, which is what lets a caller
   * hand over plain summaries: a row from before the stamp existed carries none of them, and so
   * does every row until the wire types carry them.
   */
  bumps: StampedBump[]
  hrefFor: (slug: string) => string
  now?: number
}

/**
 * THE CORPUS, ONE ROW PER BUMP. The `now` is taken once so fifty rows cannot disagree.
 *
 * THE SHELL IS `ratchet-ui`'s AND THE CELLS ARE STILL HERE, which is the shape of the whole
 * agreement with the sibling tool. This dashboard had factored the shell, one set of table styles
 * across three tables with the cells written out inline; the sibling had factored the cells, six
 * components over a shell it had copied privately into two files. Neither half is worth giving up,
 * so `DataTable` takes the shell and a `cell` function per column takes everything that knows what
 * a verdict, a CVE count or a bill-of-materials floor is. That is the part that cannot be shared:
 * it reaches for a vocabulary this server owns and the other one has not agreed to.
 */
export function BumpTable({ bumps, hrefFor, now = Date.now() }: BumpTableProps) {
  const columns: Column<StampedBump>[] = [
    {
      head: 'repository',
      cell: (b) => (
        <a href={hrefFor(b.slug)} style={{ color: 'var(--text-primary)', textDecoration: 'none' }}>
          {b.repo}
        </a>
      ),
    },
    {
      head: 'hop',
      cellStyle: { color: 'var(--text-tertiary)' },
      cell: (b) => (
        <>
          {b.from} → {b.to}
        </>
      ),
    },
    {
      head: 'verdict',
      cell: (b) => <VerdictPill verdict={b.verdict} href={hrefFor(b.slug)} />,
    },
    {
      head: 'tests',
      align: 'right',
      cell: (b) =>
        b.preTests == null ? (
          '—'
        ) : (
          <>
            {b.preTests}
            {b.lost !== undefined && b.lost > 0 ? (
              <span style={{ color: 'var(--cve-introduced)' }}> −{b.lost}</span>
            ) : null}
          </>
        ),
    },
    {
      head: 'CVEs',
      align: 'right',
      // THE NUMBERS ARE THE LINK. "77 -> 12" is the summary of a table that says which dependencies
      // moved, and that table was reachable only by clicking the repo name and scrolling past
      // everything else. A reader who wants the detail is already pointing at the number that
      // summarises it.
      cell: (b) =>
        b.cvesBefore == null ? (
          '—'
        ) : (
          <a
            href={`${hrefFor(b.slug)}#dependencies`}
            style={{ color: 'inherit', textDecoration: 'none' }}
            title="which dependencies moved"
          >
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
          </a>
        ),
    },
    {
      head: 'BOM compliance',
      align: 'right',
      // A GREEN GATE AND A COMPLIANT PROJECT ARE DIFFERENT CLAIMS. The verdict says the project
      // builds under the target and kept every test it had. This says how many of the floors that
      // target actually needs it reached, measured against the dependency tree the build resolved
      // rather than against what a build file asked for, because a managed project declares almost
      // none of them.
      //
      // A DASH, NOT NOUGHT PER CENT. A bump that settled before anything was measured and a project
      // that declares none of these floors have both failed nothing, and rendering either as 0%
      // ranks them below a project that met half.
      cell: (b) =>
        b.bomMet == null || b.bomMet + (b.bomMissed ?? 0) === 0 ? (
          <span style={{ color: 'var(--text-tertiary)' }} title="nothing measured here">
            —
          </span>
        ) : (
          <span title={b.bomOutstanding ?? 'every floor this project declares is met'}>
            <span
              style={{
                color:
                  (b.bomMissed ?? 0) === 0
                    ? 'var(--cve-cleared)'
                    : b.bomMet === 0
                      ? 'var(--cve-introduced)'
                      : 'var(--cve-remaining)',
              }}
            >
              {b.bomMet}
            </span>
            <span style={{ color: 'var(--text-tertiary)' }}>
              {' / '}
              {b.bomMet + (b.bomMissed ?? 0)}
            </span>
            <span style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>
              {'  '}
              {Math.round((b.bomMet * 100) / (b.bomMet + (b.bomMissed ?? 0)))}%
            </span>
          </span>
        ),
    },
    {
      head: 'took',
      align: 'right',
      // HOW LONG IT HAS BEEN GOING. Beside the column on its right this is the whole diagnosis: old
      // and recently active is slow, old and silent is stuck.
      //
      // `null` IS THIS PAGE'S WORD FOR "NOT STARTED" and the component takes it rather than working
      // it out, because the two dashboards keep that fact in different fields: a start stamp here, a
      // positive span there.
      cell: (b) => (
        <TimeSpent ms={b.startedAt === 0 ? null : took(b, now)} events={b.events} />
      ),
    },
    {
      head: 'a person would have',
      align: 'right',
      // THE ESTIMATE, AND ONLY EVER ITS OWN COLUMN. It is what the estimator triad priced the work
      // that LANDED at, checked against the log by a verifier. Putting it beside `took` is the whole
      // point; adding it to anything measured would be laundering a guess into a number.
      cell: (b) => <HumanCost minutes={b.humanMinutes ?? null} />,
    },
    {
      head: 'last event',
      align: 'right',
      // A queued row has no event yet, and "56 years ago" is worse than saying so.
      cell: (b) =>
        b.at === 0 ? (
          <span style={{ color: 'var(--text-tertiary)' }}>—</span>
        ) : (
          <span style={{ color: stale(b, now) ? 'var(--verdict-again)' : undefined }}>
            <RelativeTime at={b.at} now={now} />
          </span>
        ),
    },
    {
      head: 'pipeline',
      // WHAT THE COLUMN MEANS, ON THE HEADING. The cells carry the four fields as recorded; the
      // notation they are compressed into has to be explained once, and once is here rather than
      // repeated on every row.
      headTitle:
        'which pipeline produced the row: the commit its image was built from, then four characters folding the image and the prompt and bill-of-materials hashes, because an edit made from the settings page changes what the agents are handed without changing the commit. Two rows that read alike ran the same pipeline.',
      // A DIFFERENCE BETWEEN TWO ROWS IS NOT NECESSARILY A DIFFERENCE BETWEEN TWO REPOSITORIES. The
      // harness is deployed while the sweep it is running continues, and a lane keeps the image it
      // started with, so several generations of the program are alive in one corpus at once. Without
      // this column a reader comparing two bumps has no way to tell which kind of difference they
      // are looking at.
      cell: (b) => <PipelineMark stamp={b} />,
    },
  ]

  return (
    <DataTable
      rows={bumps}
      columns={columns}
      rowKey={(b) => b.slug}
      empty={<EmptyNote>No bumps yet. The sweep writes a row as soon as one starts.</EmptyNote>}
    />
  )
}

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
 * One agent call can legitimately take minutes, a reasoning model on a large context does, so this
 * is not a failure, it is the threshold at which a reader should start reading rather than waiting.
 * Marked rather than hidden: the row still says what it says, in a colour that draws the eye.
 */
function stale(b: BumpSummary, now: number): boolean {
  return b.verdict === 'bumping' && b.at > 0 && now - b.at > 5 * 60_000
}
