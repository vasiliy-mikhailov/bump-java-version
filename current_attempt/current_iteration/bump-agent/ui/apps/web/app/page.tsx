'use client'

import { useEffect, useState } from 'react'
import type { BumpSummary, Summary } from '@bjv/types'
import {
  BumpTable,
  bomTotals,
  cveTotals,
  duration,
  EmptyNote,
  PageHeader,
  ProgressBar,
  relative,
  spellMinutes,
  STRIP,
  Tally,
} from '@bjv/ui'
import { href, live, read } from '@/lib/api'
import { Nav } from './nav'

/**
 * THE CORPUS. Every bump, newest event first.
 *
 * Loaded in the client because the page is statically exported: there is no server here to render
 * against, and the record moves while a sweep runs, so a server-rendered snapshot would be stale
 * before it arrived anyway.
 */
export default function Home() {
  const [bumps, setBumps] = useState<BumpSummary[] | null>(null)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    read<BumpSummary[]>('/api/bumps')
      .then(setBumps)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  // THE LIST IS POLLED AS A DELTA, and `now` is what makes that necessary rather than optional.
  //
  // The list is 1439 rows and about a megabyte, so it was fetched once and never again, while this
  // timer advanced `now` every fifteen seconds. The header stayed right because it reads the
  // polled summary; every "last event" cell in the table was computed from a timestamp frozen at
  // page load against a clock that kept moving, so the column drifted further from the truth the
  // longer the tab stayed open and only a manual refresh corrected it. A frozen column would have
  // been a smaller lie than a drifting one.
  //
  // Refetching everything would send a megabyte to update six rows. `since` returns what moved:
  // anything newer than the newest `at` already held, plus anything still running. The mark comes
  // from the data rather than from this machine's clock, because the two disagree and a delta
  // keyed on the wrong one silently drops rows.
  useEffect(() => {
    const pull = () => {
      read<Summary>('/api/summary').then(setSummary).catch(() => undefined)
      setBumps((held) => {
        if (held === null) {
          return held
        }
        const mark = held.reduce((newest, b) => (b.at > newest ? b.at : newest), 0)
        read<BumpSummary[]>(`/api/bumps?since=${mark}`)
          .then((moved) => {
            if (moved.length === 0) {
              return
            }
            setBumps((current) => {
              if (current === null) {
                return current
              }
              const fresh = new Map(moved.map((b) => [b.slug, b]))
              // ORDER IS THE SERVER'S AND MUST NOT BECOME THE DELTA'S. Replacing in place keeps
              // the row a reader is looking at where they are looking; splicing moved rows to the
              // top would reshuffle the table under the cursor every fifteen seconds.
              return current.map((b) => fresh.get(b.slug) ?? b)
            })
          })
          .catch(() => undefined)
        return held
      })
      setNow(Date.now())
    }
    pull()
    // THE SERVER SAYS WHEN. The fifteen-second timer is gone: /api/live emits `changed` on the
    // next write anywhere under results, and the page fetches its own delta then. The clock still
    // ticks on its own so "4s ago" keeps counting between writes, which is cheap and local.
    const stop = live('/api/live', { changed: () => pull() })
    const clock = setInterval(() => setNow(Date.now()), 1000)
    return () => {
      stop()
      clearInterval(clock)
    }
  }, [])

  if (failed !== null) {
    return (
      <>
        <PageHeader title="bumps" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>The record could not be read: {failed}</EmptyNote>
        </div>
      </>
    )
  }
  if (bumps === null) {
    return (
      <>
        <PageHeader title="bumps" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>Reading the record…</EmptyNote>
        </div>
      </>
    )
  }

  const passed = bumps.filter((b) => b.verdict === 'PASS').length
  const running = bumps.filter((b) => b.verdict === 'bumping').length
  const queued = bumps.filter((b) => b.verdict === 'queued').length
  const settled = bumps.length - running - queued
  const pct = bumps.length === 0 ? 0 : Math.trunc((settled * 100) / bumps.length)

  // WHEN THE SWEEP STARTED IS THE EARLIEST BUMP THAT SPOKE, not a field the server keeps. A run
  // root is archived and refilled between sweeps, so the first trace in it IS this sweep's start,
  // and nothing has to be written down for that to stay true.
  const begun = bumps.map((b) => b.startedAt).filter((n) => n > 0)
  const startedAt = begun.length === 0 ? 0 : Math.min(...begun)
  const elapsed = startedAt === 0 ? 0 : Math.max(0, now - startedAt)

  // EXTRAPOLATED, AND LABELLED AS SUCH. Per-bump cost is wildly uneven, so this is only ever the
  // average so far projected onto what is left. It is worth showing because the shape of the
  // answer (minutes, hours, days) is what a reader is deciding on, and worth labelling because
  // the digits are not.
  const remaining = bumps.length - settled
  const eta = settled === 0 || elapsed === 0 ? null : Math.round((elapsed / settled) * remaining)

  // THE ESTIMATOR'S COLUMN, SUMMED. Only settled bumps carry a price, so this grows with the run
  // and is never a projection: it is what has actually been done, valued at what a person would
  // have spent doing it.
  const humanMinutes = bumps.reduce((sum, b) => sum + (b.humanMinutes ?? 0), 0)

  // OVER THE BUMPS THAT HAVE BOTH NUMBERS, which is not the corpus and must not read as it. The
  // after scan runs only on a green gate, so a failed bump has a before and no after; counting
  // its before alone would credit the run with clearing a project it never finished. The
  // denominator travels with the totals for the same reason.
  const cve = cveTotals(bumps)
  const bom = bomTotals(bumps)

  // TWO ADJACENT STRIPS, as the sibling has: the first counts the RUN, the second counts the
  // verdicts. Merging them would put "elapsed" next to "blocked-dependency".
  const byVerdict = new Map<string, number>()
  for (const b of bumps) {
    byVerdict.set(b.verdict, (byVerdict.get(b.verdict) ?? 0) + 1)
  }

  return (
    <>
      <PageHeader
        title="bumps"
        subtitle={
          summary === null
            ? `${bumps.length} bump(s) in the corpus`
            : `${summary.bumps} bump(s) · ${summary.events.toLocaleString()} trace event(s)` +
              (summary.lastEventAt === 0
                ? ' · nothing has run yet'
                : ` · last event ${relative(summary.lastEventAt, now)}`)
        }
        actions={<Nav current="bumps" />}
      />
      <ProgressBar pct={pct} />
      <div style={STRIP}>
        <Tally value={`${settled} / ${bumps.length}`} label={`${pct}% settled`} />
        <Tally value={elapsed === 0 ? '—' : duration(elapsed)} label="elapsed" />
        <Tally value={eta === null ? '—' : duration(eta)} label="eta, extrapolated" />
        <Tally
          value={humanMinutes === 0 ? '—' : spellMinutes(humanMinutes)}
          label="human-equivalent"
        />
        <Tally value={passed} label="passed" tone="good" />
        <Tally value={running} label="running" />
        <Tally value={queued} label="queued" />
      </div>
      <div style={{ ...STRIP, paddingTop: 0 }}>
        <Tally value={cve.before.toLocaleString()} label="CRITICAL+HIGH before" />
        <Tally
          value={cve.after.toLocaleString()}
          label="after"
          tone={cve.after < cve.before ? 'good' : cve.after > cve.before ? 'alarm' : 'plain'}
        />
        {/* THE RATE IS THE QUESTION, so it is the link. A percentage with nothing behind it
            is a number to believe or not; the page it opens says which dependencies account
            for it and which repositories did the work. */}
        <a href={href('/security/')} style={{ textDecoration: 'none', color: 'inherit' }}>
          <Tally
            value={cve.rate === null ? '—' : `${cve.rate}%`}
            label={`removed, over ${cve.measured.toLocaleString()} measured →`}
            tone={cve.rate !== null && cve.rate > 0 ? 'good' : cve.rate !== null && cve.rate < 0 ? 'alarm' : 'plain'}
          />
        </a>
      </div>
      {/* THE SAME SHAPE, ASKING THE OTHER QUESTION. The row above is what the bumps did to the
          vulnerabilities; this is what they did to the versions the target actually needs, which
          the verdict does not say and which a green gate can be entirely silent about.

          Both rows count only bumps measured on BOTH sides. A before with no after would read as
          work done and an after with no before as work lost, and neither is true. */}
      <div style={{ ...STRIP, paddingTop: 0 }}>
        <Tally value={bom.before.toLocaleString()} label="BOM issues before" />
        <Tally
          value={bom.after.toLocaleString()}
          label="after"
          tone={bom.after < bom.before ? 'good' : bom.after > bom.before ? 'alarm' : 'plain'}
        />
        <Tally
          value={bom.removed === null ? '—' : `${bom.removed}%`}
          label={`removed, over ${bom.measured.toLocaleString()} measured`}
          tone={
            bom.removed !== null && bom.removed > 0
              ? 'good'
              : bom.removed !== null && bom.removed < 0
                ? 'alarm'
                : 'plain'
          }
        />
        {/* The rate is over floors that APPLIED, so a repository declaring none of them neither
            helps nor hurts it. The definition lives one click away rather than in a tooltip. */}
        <a href={href('/settings/?a=bom')} style={{ textDecoration: 'none', color: 'inherit' }}>
          <Tally
            value={bom.compliance === null ? '—' : `${bom.compliance}%`}
            label="BOM compliance →"
            tone={
              bom.compliance === null
                ? 'plain'
                : bom.compliance >= 90
                  ? 'good'
                  : bom.compliance < 50
                    ? 'alarm'
                    : 'plain'
            }
          />
        </a>
      </div>
      <div style={{ ...STRIP, paddingTop: 0 }}>
        {[...byVerdict.entries()]
          .filter(([v]) => v !== 'PASS' && v !== 'bumping' && v !== 'queued')
          .sort((a, b) => b[1] - a[1])
          .map(([verdict, n]) => (
            <Tally key={verdict} value={n} label={verdict} />
          ))}
      </div>
      <BumpTable
        bumps={bumps}
        now={now}
        hrefFor={(slug) => href(`/bump/?slug=${encodeURIComponent(slug)}`)}
      />
    </>
  )
}
