import type { BumpSummary, Verdict } from '@bjv/types'
import { DataTable, EmptyNote, HumanCost, Lamp, TimeSpent, type Column } from 'ratchet-ui/components'
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
      /**
       * THE TWO FACTS THE VERDICT WORD DOES NOT CARRY, and which this server has been computing on
       * every row and sending to a page that could not show them. `baselineGreen` and `gateGreen`
       * have been on the wire since they were added; until now the only thing in this repository
       * that read either was a test fixture.
       *
       * LEFT IS THE BASELINE, taken under the project's own JDK before anything moved. RIGHT IS THE
       * GATE, which is what settled the bump. They are not a proof in sequence, they are two
       * questions asked at two moments, and only the second one is about the bump.
       *
       * THE COLOUR AND THE SENTENCES ARE THIS DASHBOARD'S. `Lamp` came from the sibling tool's
       * request and takes both as props precisely so that neither pipeline's vocabulary travels: a
       * green lamp there means a test that was made to fail and then passed, and it means nothing
       * of the sort here.
       *
       * ONE COLOUR FOR BOTH LAMPS, WHICH IS HONEST HERE AND WOULD NOT BE THERE. The sibling's two
       * lamps are red and green because its two questions want opposite answers. Both of these ask
       * "was this green", so both are `--state-pass` and the reader tells them apart by position,
       * explained once on the heading rather than repeated on every row. That is what this table
       * already does for `pipeline`.
       */
      head: 'green',
      headTitle:
        'two lamps: the baseline before the bump, then the gate after it. Filled means it went green, a faint ring means it ran and did not, and a dashed outline means it was never reached.',
      cell: (b) => (
        <span
          style={{ display: 'flex', gap: '5px', alignItems: 'center' }}
          role="group"
          aria-label="what the builds said"
        >
          <Lamp
            lit={b.baselineGreen}
            reached={b.verdict !== 'queued'}
            colour="var(--state-pass)"
            label={baselineSays(b)}
          />
          <Lamp
            lit={b.gateGreen}
            reached={gateRan(b.verdict)}
            colour="var(--state-pass)"
            label={gateSays(b)}
          />
        </span>
      ),
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
      /**
       * WHICH ROUND OF THE LANE BUDGET THIS IS, AND BLANK WHILE THAT MEANS NOTHING.
       *
       * A lane has a wall clock: when it runs out the bump stops between stages, keeps its
       * checkout, and goes back to the queue with this number one higher. Six rounds in seven
       * finish inside the first one, so a column that printed "1" on nearly every row would be a
       * column a reader learns to ignore. It shows something exactly when there is something to
       * see, which is the row that has held a lane more than once.
       *
       * BLANK COVERS TWO DIFFERENT FACTS AND THAT IS DELIBERATE. Round one, and a row from before
       * lanes had a budget at all. Neither is anything a reader has to act on, and a column that
       * distinguished them would spend every row saying so.
       */
      head: 'round',
      align: 'right',
      headTitle:
        'how many lane budgets this bump has taken. Blank on the first, which is nearly all of them. It counts rounds of the SAME attempt: when the harness is deployed under a paused bump the run starts over and this goes back to one, and the record page shows every boundary so that case is visible.',
      cell: (b) =>
        b.round == null || b.round === '1' ? (
          <span style={{ color: 'var(--text-tertiary)' }} />
        ) : (
          <span style={{ color: 'var(--verdict-again)' }} title={`round ${b.round} of this attempt`}>
            {b.round}
          </span>
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
 * THE ONE SETTLEMENT THAT ENDS A BUMP BEFORE THERE IS ANYTHING TO GATE.
 *
 * `no-baseline` is thrown from the baseline stage itself, and from nowhere else: `Bump.java` raises
 * it when the checkout already carries this harness's commits, when the project does not build
 * under its own JDK, and when no test passed under it. All three are before the gate is a
 * possibility, so there is no gate behind the word and the right-hand lamp is hollow: never ran,
 * which is a different answer from ran and did not go green.
 *
 * TWO OTHER WORDS WERE IN THIS SET AND THE SETTLEMENT RECORD SAYS THEY DO NOT BELONG.
 * `NO_BASELINE_NOTESTS` is `Gate.decide`'s own return value, reached only after the gate has built
 * and tested the repository under the target JDK, so the gate ran and answered. `infra` has two
 * producers: one in the baseline stage for a half-staged Gradle distribution, which is before the
 * gate, and one in the arguer, which `Flow.when` runs only when the gate has already run and was
 * not green. Every one of the seventeen `infra` rows in this corpus is the arguer's.
 *
 * THE TWO INFRA PRODUCERS ARE INDISTINGUISHABLE ON THE WIRE, so this is a choice between two
 * errors rather than a way of avoiding one. The settlement writes `gate=false` whether the gate
 * answered no or never got a turn, and `Corpus.java` reads that one boolean, so a reader of the
 * page cannot be told which happened. Hollow was wrong for every `infra` row that exists; dim is
 * wrong only for a producer that has yet to write one.
 */
const NO_GATE_BEHIND_IT = new Set<Verdict>(['no-baseline'])

/**
 * Whether the gate got a turn at all.
 *
 * A ROW STILL BUMPING IS HOLLOW TOO, and this is the case worth being careful about. The gate has
 * not spoken yet; drawing it dim would say it ran and did not go green, which is inventing the half
 * of the answer that has not arrived.
 */
function gateRan(verdict: Verdict): boolean {
  // `paused` IS MID-FLIGHT IN EVERY SENSE, so it belongs with `bumping` rather than with a verdict.
  // The bump stopped between two stages and the gate may not have had its turn yet; drawing the
  // lamp dim would say it ran and did not go green, which invents the half of the answer that has
  // not arrived. `out-of-rounds` is the same fact with the harness having given up.
  return (
    verdict !== 'queued' &&
    verdict !== 'bumping' &&
    verdict !== 'paused' &&
    verdict !== 'out-of-rounds' &&
    !NO_GATE_BEHIND_IT.has(verdict)
  )
}

/**
 * WHAT THE LEFT LAMP MEANS, IN THIS DASHBOARD'S OWN SENTENCE.
 *
 * `baselineGreen` is `preTest.passed()`, written the moment the pre-bump suite has run under the
 * project's OWN JDK, before anything is touched. It is not "the bump worked". It qualifies the
 * CONSERVED SET: when it is false the harness still proceeds, and its own account says so, the
 * suite is not all green and the red ones were red before this bump and are not in the set. So a
 * dim baseline lamp is a statement about what this bump can be held to, not a failure of it.
 */
function baselineSays(b: BumpSummary): string {
  if (b.verdict === 'queued') {
    return 'nothing has run yet, so no baseline has been taken'
  }
  // A `no-baseline` ROW IS THE BASELINE STAGE'S OWN ACCOUNT AND NEITHER GENERAL SENTENCE FITS IT.
  // Both of the ones below read the boolean as a fact about a suite that ran, and on this word it
  // is not one. The record carries three of these: two where the project could not be built or
  // tested at all, which sent `baseline=false`, and one where nothing failed under the project's
  // own JDK because nothing ran, which sent `baseline=true` and would otherwise be labelled "every
  // test passed" beside a verdict saying there was no baseline. That pair contradicts itself on a
  // row a reader can see today.
  if (b.verdict === 'no-baseline') {
    return b.baselineGreen
      ? `nothing failed under JDK ${b.from}, and nothing passed either, so there is no set of tests for this bump to be held to`
      : `the project could not be built or tested under its own JDK ${b.from}, so no baseline was ever taken`
  }
  if (b.baselineGreen) {
    return `the baseline was green: every test passed under JDK ${b.from} before anything was changed`
  }
  return `the baseline ran and was not all green; the tests that were already red under JDK ${b.from} are not in the set this bump has to conserve`
}

/**
 * WHAT THE RIGHT LAMP MEANS, and it is the field the closers select on: the after-scan runs only
 * when the gate went green and the arguer only when it did not.
 *
 * The verdict column already shows the WORD this produced. The lamp shows the fact underneath it,
 * which is that the project built under the target JDK and kept every test the baseline was
 * holding.
 */
function gateSays(b: BumpSummary): string {
  if (b.gateGreen) {
    return `the gate went green: it built under JDK ${b.to} and kept every test the baseline was holding`
  }
  // THE HARNESS FAILING IS STILL AN ANSWER FROM THE GATE, on every row this corpus has. The word
  // comes from the arguer, which runs only when the gate has already run and was not green. It
  // used to say the harness failed BEFORE the gate could run, which was true of a producer that
  // has written no row and false of all seventeen that exist.
  if (b.verdict === 'infra') {
    return 'the gate never went green, and what settled this bump was the harness failing rather than the project'
  }
  // `Gate.decide`'s OWN FIRST BRANCH, so the gate built and tested and then had nothing to hold the
  // result to. It reaches a settled row only through a resume whose journal recalled an empty
  // baseline; a first run settles `no-baseline` in the baseline stage and never gets here.
  if (b.verdict === 'NO_BASELINE_NOTESTS') {
    return `the gate ran under JDK ${b.to} and had no baseline set to hold the project to, so there was nothing to conserve`
  }
  // BETWEEN ROUNDS, WHICH IS NOT THE SAME AS NOT STARTED. The lane ran out of its wall clock and
  // stopped between two stages; whether the gate gets a turn is the next lane's business.
  if (b.verdict === 'paused') {
    return 'the round ended before the gate reached a verdict; the next lane continues from where this one stopped'
  }
  if (b.verdict === 'out-of-rounds') {
    return 'the harness stopped spending lanes on this bump before the gate reached a verdict'
  }
  if (gateRan(b.verdict)) {
    return 'the gate ran and never went green, which is what settled this bump'
  }
  if (NO_GATE_BEHIND_IT.has(b.verdict)) {
    return 'there was no baseline to gate against, so the gate never ran'
  }
  return 'the gate has not spoken for this bump yet'
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
