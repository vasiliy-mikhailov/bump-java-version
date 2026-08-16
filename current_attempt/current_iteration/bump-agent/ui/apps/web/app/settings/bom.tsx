'use client'

import { useEffect, useState } from 'react'
import { Card, EmptyNote, SaveRow, type Style } from '@bjv/ui'
import { href, read } from '@/lib/api'

type File = {
  part: 'enables' | 'hardens'
  title: string
  about: string
  rows: number
  text: string
  edited: boolean
}

type Bom = { hop: string; name: string; files: File[] }

const HOPS = ['8-11', '11-17', '17-21', '21-25']

/**
 * THE TWO LISTS A HOP WORKS TO, AS THE TWO FILES THEY ARE.
 *
 * There was a rendered table here and it is gone. It was a second shape of the same fact, kept in
 * step by hand, and it got the fact wrong the first time one artifact needed two rows: the row key
 * was the coordinate, tomcat-embed-core has a 9.0 head and a 10.1 head, and React drew one of them
 * four times. The file is the thing; a picture of the file is a thing to keep true.
 *
 * TWO FILES, NOT A COLUMN, because these are two kinds of claim and not two timings of one. What
 * ENABLES the bump is a precondition: below it the bump does not happen. What HARDENS the result is
 * polish on a project that already builds and tests green. A phase column inside one file made them
 * look like the same measurement taken twice, and invited a reader to weigh them the same.
 *
 * AN EDIT REPLACES THE BUILT-IN ENTIRELY, on the same terms as an edited prompt. No merge: a list
 * half from the code and half from a box is a list nobody can read in one place, and reading it in
 * one place is the only way anyone works out why a version was asked for. Saving an empty box
 * throws the edit away, because the built-in was never gone.
 */
export function BomSection() {
  const [name, setName] = useState('17-21')
  const [bom, setBom] = useState<Bom | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [said, setSaid] = useState<Record<string, string>>({})

  const load = (hop: string) =>
    read<Bom>(`/api/settings/bom?hop=${hop}`)
      .then(setBom)
      .catch((e: Error) => setFailed(e.message))

  useEffect(() => {
    setBom(null)
    setFailed(null)
    setTyped({})
    setSaid({})
    void load(name)
  }, [name])

  const save = (part: string, text: string) => {
    setBusy(part)
    setSaid((s) => ({ ...s, [part]: '' }))
    fetch(href(`/api/settings/bom?hop=${name}&part=${part}`), { method: 'POST', body: text })
      .then((r) => r.json())
      .then((r: { saved: boolean; why?: string; rows?: number; edited?: boolean }) => {
        setBusy(null)
        if (!r.saved) {
          // REFUSED HERE RATHER THAN AT THE NEXT BUMP. A row that cannot be read throws at load,
          // and load happens inside a lane, where nobody who typed it would ever see the message.
          setSaid((s) => ({ ...s, [part]: `not saved: ${r.why ?? 'unknown'}` }))
          return
        }
        setSaid((s) => ({
          ...s,
          [part]: r.edited
            ? `saved, ${r.rows} row(s); this replaces the built-in`
            : `reverted, ${r.rows} row(s) from the code`,
        }))
        setTyped((s) => {
          const next = { ...s }
          delete next[part]
          return next
        })
        void load(name)
      })
      .catch((e: Error) => {
        setBusy(null)
        setSaid((s) => ({ ...s, [part]: `not saved: ${e.message}` }))
      })
  }

  if (failed !== null) {
    return <EmptyNote>The bills of materials could not be read: {failed}</EmptyNote>
  }

  return (
    <>
      {/* ONE HOP AT A TIME. Lombok is in all four lists at two different versions, and four pairs
          of files side by side invite a reader to compare rows that answer different questions. */}
      <nav style={RUNGS} aria-label="Which hop">
        {HOPS.map((h) => (
          <button
            key={h}
            type="button"
            onClick={() => setName(h)}
            style={rungStyle(h === name)}
            aria-current={h === name ? 'true' : undefined}
          >
            {'JDK '}
            {h.replace('-', ' → ')}
          </button>
        ))}
      </nav>

      {bom === null ? (
        <EmptyNote>Reading the bills of materials…</EmptyNote>
      ) : (
        bom.files.map((f) => (
          <section key={f.part} style={{ marginBottom: '22px' }}>
            <h2 style={LABEL}>
              {f.title}
              <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>
                {'  '}
                {f.rows}
                {' row(s)'}
              </span>
              {f.edited ? <span style={EDITED}>edited</span> : null}
            </h2>
            <p style={ABOUT}>{f.about}</p>
            <Card>
              <textarea
                value={typed[f.part] ?? f.text}
                onChange={(e) => setTyped((s) => ({ ...s, [f.part]: e.target.value }))}
                spellCheck={false}
                rows={20}
                style={EDITOR}
                aria-label={`${f.title}, JDK ${bom.hop}`}
              />
              <SaveRow
                onSave={() => save(f.part, typed[f.part] ?? f.text)}
                busy={busy === f.part}
                said={
                  said[f.part] !== undefined && said[f.part] !== ''
                    ? said[f.part]
                    : typed[f.part] === undefined
                      ? undefined
                      : 'unsaved; a bump reads what is on disk when it starts'
                }
              />
            </Card>
          </section>
        ))
      )}

      <p style={NOTE}>
        Every row is the head of a line and answers only for projects already on it: same major,
        same minor, lower patch. That is why <code>tomcat-embed-core</code> can hold a 9.0 head and a
        10.1 head at once without the two competing, and why a project on neither is asked for
        nothing — Tomcat 9 to Tomcat 10 is the jakarta rename, not a patch. Saving an empty box
        reverts to the file in <code>src/main/resources/bom/</code>. An edit takes effect on the next
        bump that starts, never on one already running.
      </p>
    </>
  )
}

const RUNGS: Style = { display: 'flex', gap: '6px', marginBottom: '18px' }

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

const LABEL: Style = {
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  margin: '0 0 6px',
}

const ABOUT: Style = {
  margin: '0 0 10px',
  fontSize: '12.5px',
  color: 'var(--text-secondary)',
  maxWidth: '90ch',
  lineHeight: 1.5,
}

const EDITED: Style = { marginLeft: '8px', color: 'var(--accent-primary)', fontWeight: 600 }

const EDITOR: Style = {
  width: '100%',
  boxSizing: 'border-box',
  font: 'inherit',
  fontFamily: 'ui-monospace, monospace',
  fontSize: '12px',
  lineHeight: 1.5,
  padding: '10px',
  borderRadius: '6px',
  border: '1px solid var(--border-soft)',
  background: 'var(--bg-canvas)',
  color: 'var(--text-primary)',
  resize: 'vertical',
  marginBottom: '12px',
}

const NOTE: Style = {
  margin: '4px 0 0',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  maxWidth: '90ch',
  lineHeight: 1.5,
}
