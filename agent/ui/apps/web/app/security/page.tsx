'use client'

import { Fragment, useEffect, useState } from 'react'
import type { Security } from '@bjv/types'
import {
  CELL,
  CELL_NESTED,
  Card,
  Disclosure,
  HEAD,
  HEAD_NESTED,
  Loaded,
  MONO,
  PageHeader,
  ROW,
  STRIP,
  Section,
  TABLE,
  Tally,
} from '@bjv/ui'
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

  const cleared = (p: { before: number; after: number }) => p.before - p.after

  return (
    <Loaded what="record" failed={failed} value={data} header={header('—')}>
      {(data) => (
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

          <Section title="by package, best outcome first" gutter="heading">
            <div style={{ overflowX: 'auto' }}>
              <table style={TABLE}>
                <thead>
                  <tr>
                    <th style={HEAD}>package</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>bumps</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>before</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>after</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>cleared</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byPackage.map((p) => (
                    <Fragment key={p.name}>
                      <tr style={ROW} data-row="package">
                        <td style={{ ...CELL, fontFamily: MONO }}>{p.name}</td>
                        <td style={{ ...CELL, textAlign: 'right', color: 'var(--text-tertiary)' }}>
                          {p.bumps}
                        </td>
                        <td style={{ ...CELL, textAlign: 'right' }}>{p.before}</td>
                        <td style={{ ...CELL, textAlign: 'right' }}>{p.after}</td>
                        <td style={{ ...CELL, textAlign: 'right', color: tone(cleared(p)) }}>
                          {cleared(p) > 0 ? `−${cleared(p)}` : cleared(p) < 0 ? `+${-cleared(p)}` : '0'}
                        </td>
                      </tr>
                      {p.versions.length === 0 ? null : (
                        <tr>
                          {/* THE SECOND LEVEL. "tomcat-embed-core 238 -> 81" is the corpus's answer
                              and not an explanation: it does not say which version is the one that
                              fixed it. The DESTINATION does, and it is also the thing a reader can go
                              and set. The source is dropped on purpose: keeping it turned tomcat into
                              thirteen rows to make one point, seven versions all landing on 10.1.55
                              and clearing everything. Folded, because 144 packages unfolded is a
                              wall. */}
                          <td colSpan={5} style={{ padding: '0 24px 6px' }}>
                            <Disclosure
                              summary={`${p.versions.length} destination version(s), best first`}
                            >
                              <table style={{ ...TABLE, fontSize: '12px' }}>
                                <thead>
                                  <tr>
                                    <th style={HEAD_NESTED}>ended up at</th>
                                    <th style={{ ...HEAD_NESTED, textAlign: 'right' }}>bumps</th>
                                    <th style={{ ...HEAD_NESTED, textAlign: 'right' }}>before</th>
                                    <th style={{ ...HEAD_NESTED, textAlign: 'right' }}>after</th>
                                    <th style={{ ...HEAD_NESTED, textAlign: 'right' }}>cleared</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {p.versions.map((v) => (
                                    <tr key={String(v.to)} style={ROW} data-row="pair">
                                      <td style={CELL_NESTED}>
                                        {/* NOT IN THE AFTER SCAN IS NOT A VERSION. The upgrade dropped
                                            or replaced it — fastjson became fastjson2 — and writing a
                                            blank there would read as "unchanged". */}
                                        {v.to === null ? (
                                          <span
                                            style={{ color: 'var(--text-tertiary)' }}
                                            title="not present in the after scan: dropped or replaced"
                                          >
                                            gone
                                          </span>
                                        ) : (
                                          v.to
                                        )}
                                      </td>
                                      <td
                                        style={{
                                          ...CELL_NESTED,
                                          textAlign: 'right',
                                          color: 'var(--text-tertiary)',
                                        }}
                                      >
                                        {v.bumps}
                                      </td>
                                      <td style={{ ...CELL_NESTED, textAlign: 'right' }}>{v.before}</td>
                                      <td style={{ ...CELL_NESTED, textAlign: 'right' }}>{v.after}</td>
                                      <td
                                        style={{
                                          ...CELL_NESTED,
                                          textAlign: 'right',
                                          color: tone(cleared(v)),
                                        }}
                                      >
                                        {cleared(v) > 0
                                          ? `−${cleared(v)}`
                                          : cleared(v) < 0
                                            ? `+${-cleared(v)}`
                                            : '0'}
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </Disclosure>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>

          <Section title="by bump, most cleared first" gutter="heading">
            <div style={{ overflowX: 'auto' }}>
              <table style={TABLE}>
                <thead>
                  <tr>
                    <th style={HEAD}>repository</th>
                    <th style={HEAD}>hop</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>before</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>after</th>
                    <th style={{ ...HEAD, textAlign: 'right' }}>cleared</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byBump.map((b) => (
                    <tr key={b.slug} style={ROW} data-row="bump">
                      <td style={CELL}>
                        <a
                          href={href(`/bump/?slug=${encodeURIComponent(b.slug)}#dependencies`)}
                          style={{ color: 'var(--text-primary)', textDecoration: 'none' }}
                        >
                          {b.repo}
                        </a>
                      </td>
                      <td style={{ ...CELL, color: 'var(--text-tertiary)' }}>
                        {b.from} → {b.to}
                      </td>
                      <td style={{ ...CELL, textAlign: 'right' }}>{b.before}</td>
                      <td style={{ ...CELL, textAlign: 'right' }}>{b.after}</td>
                      <td style={{ ...CELL, textAlign: 'right', color: tone(cleared(b)) }}>
                        {cleared(b) > 0 ? `−${cleared(b)}` : cleared(b) < 0 ? `+${-cleared(b)}` : '0'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Section>
        </>
      )}
    </Loaded>
  )
}

function tone(n: number): string {
  return n > 0 ? 'var(--cve-cleared)' : n < 0 ? 'var(--cve-introduced)' : 'var(--text-tertiary)'
}
