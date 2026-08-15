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
import { href, read } from '@/lib/api'
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
        actions={<Nav current="bumps" />}
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

          {cves.before === 0 && cves.after === 0 ? null : (
            <Section title="vulnerabilities">
              <SecurityDelta
                before={cves.before}
                after={cves.after}
                distinctBefore={cves.distinctBefore}
                distinctAfter={cves.distinctAfter}
              />
            </Section>
          )}

          <section style={{ margin: '0 0 22px' }}>
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
