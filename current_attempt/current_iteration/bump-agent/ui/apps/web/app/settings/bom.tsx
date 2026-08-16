'use client'

import { useEffect, useState } from 'react'
import { Card, EmptyNote, type Style } from '@bjv/ui'
import { read } from '@/lib/api'

type Floor = {
  coordinates: string
  artifact: string
  version: string
  phase: 'before' | 'after'
  dialect: 'any' | 'maven' | 'gradle'
  spellings: string[]
  why: string
}

type Bom = { target: number; floors: Floor[] }

/**
 * WHAT EACH TARGET NEEDS, AND WHY, AS THE HARNESS HOLDS IT.
 *
 * The other settings sections show a value somebody can change. This one shows a list nothing on
 * this page can change, and it earns its place anyway: it is the standard every passing repository
 * is scored against, and until now the only way to read it was to open a Java file. A number in a
 * table column that nobody can see the definition of is a number nobody can argue with.
 *
 * THE REASON TRAVELS WITH THE VERSION. A floor without one is indistinguishable from a
 * superstition, and these accumulate. Every version here was measured on this corpus rather than
 * read off a compatibility table, and the sentence is where that shows.
 */
export function BomSection() {
  const [boms, setBoms] = useState<Bom[] | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [target, setTarget] = useState(21)

  useEffect(() => {
    read<Bom[]>('/api/settings/bom')
      .then(setBoms)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  if (failed !== null) {
    return <EmptyNote>The bills of materials could not be read: {failed}</EmptyNote>
  }
  if (boms === null) {
    return <EmptyNote>Reading the bills of materials…</EmptyNote>
  }

  const shown = boms.find((b) => b.target === target) ?? boms[0]
  if (shown === undefined) {
    return <EmptyNote>There are no bills of materials.</EmptyNote>
  }

  return (
    <>
      {/* ONE RUNG AT A TIME. Lombok appears in all four lists at two different versions, and four
          tables side by side invite a reader to compare rows that are answers to different
          questions. The hop picker is the same shape the prompts section uses. */}
      <nav style={RUNGS} aria-label="Which target">
        {boms.map((b) => (
          <button
            key={b.target}
            type="button"
            onClick={() => setTarget(b.target)}
            style={rungStyle(b.target === shown.target)}
            aria-current={b.target === shown.target ? 'true' : undefined}
          >
            {'Java '}
            {b.target}
            <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>
              {'  '}
              {b.floors.length}
            </span>
          </button>
        ))}
      </nav>

      <Card>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' }}>
          <thead>
            <tr>
              <th style={TH}>artifact</th>
              <th style={{ ...TH, textAlign: 'right', whiteSpace: 'nowrap' }}>at least</th>
              <th style={TH}>phase</th>
              <th style={TH}>why it is here</th>
            </tr>
          </thead>
          <tbody>
            {shown.floors.map((f) => (
              <tr key={f.coordinates}>
                <td style={TD}>
                  <div style={{ fontFamily: 'ui-monospace, monospace' }}>{f.artifact}</div>
                  <div style={{ color: 'var(--text-tertiary)', fontSize: '11px' }}>
                    {f.coordinates.slice(0, f.coordinates.lastIndexOf(':'))}
                    {/* A row one build system cannot express does not count against it, and a
                        reader looking at a repository scored 3 of 4 needs to know which rows were
                        never in play. */}
                    {f.dialect === 'any' ? null : (
                      <span style={ONLY}>{f.dialect} only</span>
                    )}
                  </div>
                  {/* The names the same artifact goes by elsewhere. Without these the measurement
                      is a lie on half the corpus: Gradle cannot write spring-boot-starter-parent. */}
                  {f.spellings.length === 0 ? null : (
                    <div style={{ color: 'var(--text-tertiary)', fontSize: '11px', marginTop: '2px' }}>
                      {'also '}
                      {f.spellings.join(', ')}
                    </div>
                  )}
                </td>
                <td
                  style={{
                    ...TD,
                    textAlign: 'right',
                    fontFamily: 'ui-monospace, monospace',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {f.version}
                </td>
                <td style={{ ...TD, whiteSpace: 'nowrap' }}>
                  <span style={f.phase === 'after' ? AFTER : BEFORE}>{f.phase} the JDK</span>
                </td>
                <td style={{ ...TD, color: 'var(--text-secondary)', lineHeight: 1.45 }}>
                  {f.why === '' ? (
                    <span style={{ color: 'var(--text-tertiary)' }}>
                      no reason is recorded, which makes this indistinguishable from a superstition
                    </span>
                  ) : (
                    f.why
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <p style={NOTE}>
        {/* Said here because a reader is entitled to know how much this page can be trusted, and
            because the answer is about to change: the deterministic pinning step will apply these
            rather than measure against them. */}
        These are hand maintained, in <code>src/main/resources/bom/</code>, and stated a second time
        as prose in <code>Floors.java</code>, which is what the planners actually read. A test walks
        both and fails the build the moment a coordinate or a version disagrees. Nothing applies them
        yet; every passing repository is scored against them.
      </p>
    </>
  )
}

const RUNGS: Style = { display: 'flex', gap: '6px', marginBottom: '14px' }

function rungStyle(current: boolean): Style {
  return {
    font: 'inherit',
    fontSize: '12.5px',
    fontWeight: current ? 600 : 400,
    padding: '5px 11px',
    borderRadius: '6px',
    cursor: 'pointer',
    border: '1px solid var(--border-soft)',
    color: current ? 'var(--text-primary)' : 'var(--text-tertiary)',
    background: current ? 'var(--state-selected-bg)' : 'transparent',
  }
}

const TH: Style = {
  textAlign: 'left',
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  padding: '0 12px 8px 0',
  borderBottom: '1px solid var(--border-soft)',
}

const TD: Style = {
  padding: '9px 12px 9px 0',
  borderBottom: '1px solid var(--border-soft)',
  verticalAlign: 'top',
}

const ONLY: Style = {
  marginLeft: '6px',
  padding: '0 5px',
  borderRadius: '4px',
  background: 'var(--bg-subtle)',
  color: 'var(--text-tertiary)',
}

const BEFORE: Style = { color: 'var(--text-tertiary)' }
const AFTER: Style = { color: 'var(--accent-primary)' }

const NOTE: Style = {
  margin: '14px 0 0',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  maxWidth: '80ch',
  lineHeight: 1.5,
}
