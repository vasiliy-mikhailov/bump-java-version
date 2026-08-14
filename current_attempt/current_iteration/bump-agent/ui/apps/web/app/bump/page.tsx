'use client'

import { Suspense, useEffect, useState } from 'react'
import type { BumpDetail } from '@bjv/types'
import {
  ChainStrip,
  EmptyNote,
  EventFeed,
  PackageTable,
  PageHeader,
  SecurityDelta,
  VerdictPill,
} from '@bjv/ui'
import { href, read } from '@/lib/api'
import { Nav } from '../nav'

/**
 * ONE BUMP: the chain it walked, what it did, and what it cost.
 *
 * The slug and the agent filter live in the query string rather than the path because the page is
 * statically exported — one HTML file serves every bump, and a path segment would need one file per
 * bump generated at build, of a set that grows while the sweep runs.
 */
function BumpPage() {
  const [detail, setDetail] = useState<BumpDetail | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [slug, setSlug] = useState('')
  const [only, setOnly] = useState<string | undefined>(undefined)

  useEffect(() => {
    const q = new URLSearchParams(window.location.search)
    const s = q.get('slug') ?? ''
    const agent = q.get('agent')
    setSlug(s)
    setOnly(agent ?? undefined)
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
  const link = (agent: string) =>
    href(`/bump/?slug=${encodeURIComponent(slug)}&agent=${encodeURIComponent(agent)}`)

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

      {summary.because == null ? null : (
        <p
          style={{
            fontSize: '12px',
            color: 'var(--text-secondary)',
            margin: 0,
            padding: '12px 24px 0',
          }}
        >
          {summary.because}
        </p>
      )}

      <Section title="the chain">
        <ChainStrip
          stages={chain}
          {...(only === undefined ? {} : { only })}
          hrefFor={link}
          allHref={href(`/bump/?slug=${encodeURIComponent(slug)}`)}
        />
      </Section>

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

      <Section title={only === undefined ? 'what happened' : `what ${only} did`}>
        <EventFeed events={shown} />
      </Section>

      <section style={{ margin: '0 0 22px' }}>
        <h2
          style={{
            fontSize: '11px',
            textTransform: 'uppercase',
            letterSpacing: '.06em',
            color: 'var(--text-tertiary)',
            fontWeight: 500,
            margin: '18px 24px 10px',
          }}
        >
          dependencies
        </h2>
        <PackageTable packages={packages} />
      </section>
    </>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ margin: '0 0 22px', padding: '0 24px' }}>
      <h2
        style={{
          fontSize: '11px',
          textTransform: 'uppercase',
          letterSpacing: '.06em',
          color: 'var(--text-tertiary)',
          fontWeight: 500,
          margin: '18px 0 10px',
        }}
      >
        {title}
      </h2>
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
