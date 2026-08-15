'use client'

import { useEffect, useState } from 'react'
import type { Security } from '@bjv/types'
import { Card, EmptyNote, PageHeader, STRIP, Tally } from '@bjv/ui'
import { href, read } from '@/lib/api'
import { Nav } from '../nav'

/**
 * WHERE THE CLEARED VULNERABILITIES WENT.
 *
 * The list page can say the corpus went from N to M, because every row carries its own two
 * numbers. It cannot say WHICH dependency accounts for the difference, and that is the question
 * the percentage actually raises: a rate with nothing behind it is a number to believe or not.
 *
 * Two tables, because there are two readings. By package answers "what did raising these
 * frameworks actually fix", which is the argument for doing any of this. By bump answers "which
 * repositories did the work", which is where to look when the rate moves.
 */
export default function SecurityPage() {
  const [data, setData] = useState<Security | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Security>('/api/security')
      .then(setData)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  const header = (subtitle: string) => (
    <PageHeader
      title="vulnerabilities cleared"
      subtitle={subtitle}
      back={{ label: 'bumps', href: href('/') }}
      actions={<Nav current="security" />}
    />
  )

  if (failed !== null) {
    return (
      <>
        {header('—')}
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>The record could not be read: {failed}</EmptyNote>
        </div>
      </>
    )
  }
  if (data === null) {
    return (
      <>
        {header('—')}
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>Reading the record…</EmptyNote>
        </div>
      </>
    )
  }

  const cleared = (p: { before: number; after: number }) => p.before - p.after

  return (
    <>
      {header(
        `${data.measured.toLocaleString()} bump(s) scanned before and after · ` +
          `${data.byPackage.length.toLocaleString()} package(s) ever vulnerable`,
      )}

      <div style={STRIP}>
        <Tally value={data.before.toLocaleString()} label="distinct before" />
        <Tally
          value={data.after.toLocaleString()}
          label="distinct after"
          tone={data.after < data.before ? 'good' : 'plain'}
        />
        <Tally
          value={data.rate === null ? '—' : `${data.rate}%`}
          label="removed"
          tone={data.rate !== null && data.rate > 0 ? 'good' : 'plain'}
        />
        <Tally value={data.measured.toLocaleString()} label="bumps measured" />
      </div>

      <div style={{ padding: '0 24px 4px' }}>
        <Card tone="subtle">
          <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            {/* THE RECONCILIATION, SAID OUT LOUD. The list page counts occurrences and this counts
                distinct, so its headline is larger than the one above and a reader who noticed
                would be right to distrust both until told which is which. */}
            The list page reports {data.occurrencesBefore.toLocaleString()} →{' '}
            {data.occurrencesAfter.toLocaleString()} for the same bumps. That count is
            occurrence-based: the scan reports a finding once per module that resolves the
            dependency, so a seventeen-module project counts one CVE seventeen times. Everything on
            this page is distinct, counted once per package and version, which is what a reader
            means by &ldquo;how many vulnerabilities&rdquo;.
          </div>
        </Card>
      </div>

      <Section title="by package, best outcome first">
        <div style={{ overflowX: 'auto' }}>
          <table style={TABLE}>
            <thead>
              <tr>
                <th style={th}>package</th>
                <th style={{ ...th, textAlign: 'right' }}>bumps</th>
                <th style={{ ...th, textAlign: 'right' }}>before</th>
                <th style={{ ...th, textAlign: 'right' }}>after</th>
                <th style={{ ...th, textAlign: 'right' }}>cleared</th>
              </tr>
            </thead>
            <tbody>
              {data.byPackage.map((p) => (
                <tr key={p.name} style={ROW}>
                  <td style={{ ...td, fontFamily: 'ui-monospace, Menlo, monospace' }}>{p.name}</td>
                  <td style={{ ...td, textAlign: 'right', color: 'var(--text-tertiary)' }}>
                    {p.bumps}
                  </td>
                  <td style={{ ...td, textAlign: 'right' }}>{p.before}</td>
                  <td style={{ ...td, textAlign: 'right' }}>{p.after}</td>
                  <td style={{ ...td, textAlign: 'right', color: tone(cleared(p)) }}>
                    {cleared(p) > 0 ? `−${cleared(p)}` : cleared(p) < 0 ? `+${-cleared(p)}` : '0'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      <Section title="by bump, most cleared first">
        <div style={{ overflowX: 'auto' }}>
          <table style={TABLE}>
            <thead>
              <tr>
                <th style={th}>repository</th>
                <th style={th}>hop</th>
                <th style={{ ...th, textAlign: 'right' }}>before</th>
                <th style={{ ...th, textAlign: 'right' }}>after</th>
                <th style={{ ...th, textAlign: 'right' }}>cleared</th>
              </tr>
            </thead>
            <tbody>
              {data.byBump.map((b) => (
                <tr key={b.slug} style={ROW}>
                  <td style={td}>
                    <a
                      href={href(`/bump/?slug=${encodeURIComponent(b.slug)}#dependencies`)}
                      style={{ color: 'var(--text-primary)', textDecoration: 'none' }}
                    >
                      {b.repo}
                    </a>
                  </td>
                  <td style={{ ...td, color: 'var(--text-tertiary)' }}>
                    {b.from} → {b.to}
                  </td>
                  <td style={{ ...td, textAlign: 'right' }}>{b.before}</td>
                  <td style={{ ...td, textAlign: 'right' }}>{b.after}</td>
                  <td style={{ ...td, textAlign: 'right', color: tone(cleared(b)) }}>
                    {cleared(b) > 0 ? `−${cleared(b)}` : cleared(b) < 0 ? `+${-cleared(b)}` : '0'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </>
  )
}

function tone(n: number): string {
  return n > 0 ? 'var(--cve-cleared)' : n < 0 ? 'var(--cve-introduced)' : 'var(--text-tertiary)'
}

const TABLE = { width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' } as const
const ROW = { borderTop: '1px solid var(--border-soft)' } as const
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
const td = { padding: '8px 24px', verticalAlign: 'top' } as const

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
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
        {title}
      </h2>
      {children}
    </section>
  )
}
