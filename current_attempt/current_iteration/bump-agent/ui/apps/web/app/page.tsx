'use client'

import { useEffect, useState } from 'react'
import type { BumpSummary, Summary } from '@bjv/types'
import {
  BumpTable,
  duration,
  EmptyNote,
  PageHeader,
  ProgressBar,
  relative,
  spellMinutes,
  STRIP,
  Tally,
} from '@bjv/ui'
import { href, read } from '@/lib/api'
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

  // SEPARATE FROM THE LIST, and polled. The counts are what a reader watches to decide whether the
  // sweep is alive, and the list is 1439 rows that mostly are not moving; refetching the second to
  // refresh the first would be a megabyte a tick.
  useEffect(() => {
    const pull = () => {
      read<Summary>('/api/summary').then(setSummary).catch(() => undefined)
      setNow(Date.now())
    }
    pull()
    const timer = setInterval(pull, 15_000)
    return () => clearInterval(timer)
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
