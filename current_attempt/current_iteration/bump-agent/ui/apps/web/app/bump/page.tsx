'use client'

import { Suspense, useEffect, useState } from 'react'
import type { BumpDetail } from '@bjv/types'
import {
  Card,
  ChainStrip,
  EmptyNote,
  EventFeed,
  PackageTable,
  PageHeader,
  SecurityDelta,
  TabRow,
  VerdictPill,
} from '@bjv/ui'
import type { TraceEvent } from '@bjv/types'
import { href, live, post, read } from '@/lib/api'
import { Nav } from '../nav'

/** Which half of the page is showing. The record is everything that happened, in order. */
type Tab = 'summary' | 'record'

/**
 * ONE BUMP: the chain it walked, what it did, and what it cost.
 *
 * The slug, the tab and the agent filter live in the query string rather than the path because the
 * page is statically exported — one HTML file serves every bump, and a path segment would need one
 * file per bump generated at build, of a set that grows while the sweep runs.
 *
 * THE CHAIN SITS ABOVE THE TABS, not inside one, because it is the answer to "how far did this
 * get" and that question does not belong to either half. The sibling keeps its strip in the same
 * place for the same reason.
 */
function BumpPage() {
  const [detail, setDetail] = useState<BumpDetail | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [slug, setSlug] = useState('')
  const [only, setOnly] = useState<string | undefined>(undefined)
  const [tab, setTab] = useState<Tab>('summary')
  const [rerun, setRerun] = useState<string | null>(null)

  useEffect(() => {
    const q = new URLSearchParams(window.location.search)
    const s = q.get('slug') ?? ''
    const agent = q.get('agent')
    setSlug(s)
    setOnly(agent ?? undefined)
    // AN AGENT FILTER IS A REQUEST FOR THE RECORD. Landing on the summary having asked what one
    // agent did would answer a question nobody asked and hide the one they did.
    setTab(agent !== null || q.get('tab') === 'record' ? 'record' : 'summary')
    read<BumpDetail>(`/api/bump?slug=${encodeURIComponent(s)}`)
      .then(setDetail)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  // THE RECORD GROWS WHILE YOU READ IT. The trace is append-only, so the stream sends what was
  // added and the page puts it on the end. Nothing is refetched: a bump that has been running an
  // hour is thousands of events, and asking for all of them again to learn about one is the
  // problem this replaces.
  useEffect(() => {
    // SUBSCRIBE ONLY ONCE THE HISTORY IS IN, and tell the server how much of it we hold. The trace
    // is append-only, so a line count is a stable place to resume: start from zero and every event
    // already on screen arrives again, start from the end and anything written between the fetch
    // and the subscription is lost.
    if (slug === '' || detail === null) {
      return undefined
    }
    return live(`/api/live?slug=${encodeURIComponent(slug)}&have=${detail.events.length}`, {
      trace: (e) =>
        setDetail((held) =>
          held === null ? held : { ...held, events: [...held.events, e as TraceEvent] }),
    })
    // Deliberately not depending on `detail`: it changes with every event that arrives, and
    // resubscribing on each one would reopen the stream forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug, detail !== null])

  if (failed !== null) {
    return (
      <>
        <PageHeader title="bump" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>This bump could not be read: {failed}</EmptyNote>
        </div>
      </>
    )
  }
  if (detail === null) {
    return (
      <>
        <PageHeader title="bump" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>Reading the record…</EmptyNote>
        </div>
      </>
    )
  }

  const { summary, chain, events, packages, cves } = detail
  const shown = only === undefined ? events : events.filter((e) => e.agent === only)
  const at = (t: Tab, agent?: string) =>
    href(
      `/bump/?slug=${encodeURIComponent(slug)}&tab=${t}` +
        (agent === undefined ? '' : `&agent=${encodeURIComponent(agent)}`),
    )

  return (
    <>
      <PageHeader
        title={summary.repo}
        subtitle={
          <>
            JDK {summary.from} → {summary.to} · {summary.sha.slice(0, 12)} ·{' '}
            <VerdictPill verdict={summary.verdict} />
          </>
        }
        back={{ label: 'bumps', href: href('/') }}
        actions={
          <>
            {/* A SETTLED VERDICT IS ONLY TRUE OF THE HARNESS THAT REACHED IT. The floors, the
                prompts and the tools all moved today, so a verdict from this morning was decided
                by an agent that no longer exists. Offered only on a settled bump: asking for a
                rerun of something already running is a click that means nothing. */}
            {summary.verdict === 'bumping' || summary.verdict === 'queued' ? null : (
              <button
                type="button"
                style={RERUN}
                onClick={() => {
                  setRerun('asking…')
                  post<{ queued: boolean; why?: string; was?: string }>(
                    `/api/rerun?slug=${encodeURIComponent(slug)}`,
                  )
                    .then((r) =>
                      setRerun(
                        r.queued
                          ? `queued again, was ${r.was ?? 'settled'}`
                          : `not queued: ${r.why ?? 'unknown'}`,
                      ),
                    )
                    .catch((e: Error) => setRerun(`not queued: ${e.message}`))
                }}
              >
                rerun
              </button>
            )}
            {rerun === null ? null : (
              <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', marginRight: '8px' }}>
                {rerun}
              </span>
            )}
            <Nav current="bumps" />
          </>
        }
      />

      <div style={{ padding: '14px 24px 0' }}>
        <ChainStrip
          stages={chain}
          {...(only === undefined ? {} : { only })}
          hrefFor={(agent) => at('record', agent)}
          allHref={at('record')}
        />
      </div>

      <div style={{ padding: '12px 24px 0' }}>
        <TabRow
          label="This bump"
          tabs={[
            { label: 'summary', href: at('summary'), current: tab === 'summary' },
            { label: 'the record', href: at('record'), current: tab === 'record' },
          ]}
        />
      </div>

      {tab === 'summary' ? (
        <>
          {summary.because == null ? null : (
            <Section title="what it settled as">
              <Card>
                <div
                  style={{
                    fontSize: '12.5px',
                    color: 'var(--text-secondary)',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {summary.because}
                </div>
              </Card>
            </Section>
          )}

          {/* Nothing to show only when there was nothing before AND nothing measured after.
              `after` is null on every bump that did not reach a green gate, and null is not
              zero: a project with 337 findings and no second scan still has 337. */}
          {cves.before === 0 && (cves.after ?? 0) === 0 ? null : (
            <Section title="vulnerabilities">
              <SecurityDelta
                before={cves.before}
                after={cves.after}
                distinctBefore={cves.distinctBefore}
                distinctAfter={cves.distinctAfter}
              />
            </Section>
          )}

          <section id="dependencies" style={{ margin: '0 0 22px', scrollMarginTop: '12px' }}>
            <h2 style={LABEL}>dependencies</h2>
            <PackageTable packages={packages} />
          </section>
        </>
      ) : (
        <Section
          title={
            only === undefined
              ? `the record · ${events.length.toLocaleString()} event(s)`
              : `the record · what ${only} did · ${shown.length.toLocaleString()} of ${events.length.toLocaleString()}`
          }
        >
          {only === undefined ? null : (
            <p style={{ margin: '0 0 10px', fontSize: '12px' }}>
              <a href={at('record')} style={{ color: 'var(--accent-primary)' }}>
                show every agent
              </a>
            </p>
          )}
          <EventFeed events={shown} />
        </Section>
      )}
    </>
  )
}

const RERUN = {
  font: 'inherit',
  fontSize: '12px',
  color: 'var(--text-secondary)',
  background: 'var(--bg-subtle)',
  border: '1px solid var(--border-soft)',
  borderRadius: '5px',
  padding: '3px 10px',
  cursor: 'pointer',
  marginRight: '8px',
} as const

const LABEL = {
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  margin: '18px 24px 10px',
} as const

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ margin: '0 0 22px', padding: '0 24px' }}>
      <h2 style={{ ...LABEL, margin: '18px 0 10px' }}>{title}</h2>
      {children}
    </section>
  )
}

export default function Page() {
  return (
    <Suspense fallback={<EmptyNote>Reading the record…</EmptyNote>}>
      <BumpPage />
    </Suspense>
  )
}
