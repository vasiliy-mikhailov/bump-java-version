'use client'

import { useEffect, useState } from 'react'
import type { BumpSummary, Summary } from '@bjv/types'
import { BumpTable, EmptyNote, PageHeader, ProgressBar, relative, STRIP, Tally } from '@bjv/ui'
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
      <BumpTable bumps={bumps} hrefFor={(slug) => href(`/bump/?slug=${encodeURIComponent(slug)}`)} />
    </>
  )
}
