'use client'

import { useEffect, useState } from 'react'
import { Card, EmptyNote, SaveRow, type Style } from '@bjv/ui'
import { href, read } from '@/lib/api'

type Floor = {
  coordinates: string
  artifact: string
  version: string
  phase: 'before' | 'after'
  dialect: 'any' | 'maven' | 'gradle'
  spellings: string[]
  why: string
}

type Bom = { hop: string; name: string; floors: Floor[] }

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
  const [name, setName] = useState('17-21')
  // THE FILE, NOT THE ROWS. Its comments are half of what it says, and a round trip through
  // records and back would drop them; the same reason the endpoint serves it raw.
  const [file, setFile] = useState<{ text: string; edited: boolean } | null>(null)
  const [typed, setTyped] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<string | null>(null)

  useEffect(() => {
    setFile(null)
    setTyped(null)
    setSaid(null)
    read<{ text: string; edited: boolean }>(`/api/settings/bom/file?hop=${name}`)
      .then(setFile)
      .catch(() => setFile(null))
  }, [name])

  const save = (text: string) => {
    setBusy(true)
    setSaid(null)
    fetch(href(`/api/settings/bom/file?hop=${name}`), { method: 'POST', body: text })
      .then((r) => r.json())
      .then((r: { saved: boolean; why?: string; rows?: number; edited?: boolean }) => {
        setBusy(false)
        if (!r.saved) {
          // REFUSED HERE RATHER THAN AT THE NEXT BUMP. A row that cannot be read throws at load,
          // and load happens inside a lane, where nobody who typed it would ever see the message.
          setSaid(`not saved: ${r.why ?? 'unknown'}`)
          return
        }
        setSaid(r.edited ? `saved, ${r.rows} row(s)` : `reverted to the built-in, ${r.rows} row(s)`)
        setTyped(null)
        read<{ text: string; edited: boolean }>(`/api/settings/bom/file?hop=${name}`)
          .then(setFile)
          .catch(() => undefined)
        // The table above is parsed from the same file, so it has to be re-read too or the page
        // shows an old list beside the new text.
        read<Bom[]>('/api/settings/bom').then(setBoms).catch(() => undefined)
      })
      .catch((e: Error) => {
        setBusy(false)
        setSaid(`not saved: ${e.message}`)
      })
  }

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

  const shown = boms.find((b) => b.name === name) ?? boms[0]
  if (shown === undefined) {
    return <EmptyNote>There are no bills of materials.</EmptyNote>
  }

  return (
    <>
      {/* ONE RUNG AT A TIME. Lombok appears in all four lists at two different versions, and four
          tables side by side invite a reader to compare rows that are answers to different
          questions. The hop picker is the same shape the prompts section uses. */}
      <nav style={RUNGS} aria-label="Which hop">
        {boms.map((b) => (
          <button
            key={b.name}
            type="button"
            onClick={() => setName(b.name)}
            style={rungStyle(b.name === shown.name)}
            aria-current={b.name === shown.name ? 'true' : undefined}
          >
            {'JDK '}
            {b.hop}
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
              <th style={TH}>what it is for</th>
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
                  {/* THE TWO GROUPS ARE DIFFERENT KINDS OF ROW, not two timings. A before row is
                      what makes the bump possible at all: an unmet one means no bump. An after row
                      is polish on what the bump produced, and it is where the CVE fixes are. A
                      column that said only "before the JDK" invited a reader to weigh them the
                      same. */}
                  <span
                    style={f.phase === 'after' ? AFTER : BEFORE}
                    title={
                      f.phase === 'after'
                        ? 'polish, once the bump has already built and tested green'
                        : 'a precondition: below this the bump cannot happen at all'
                    }
                  >
                    {f.phase === 'after' ? 'hardens the result' : 'enables the bump'}
                  </span>
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

      {file === null ? null : (
        <section style={{ marginTop: '18px' }}>
          <h2 style={LABEL}>
            {'the file'}
            {file.edited ? (
              <span style={EDITED}>edited; the built-in is replaced entirely</span>
            ) : (
              <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}>
                {"  the code's own"}
              </span>
            )}
          </h2>
          <Card>
            {/* AN EDIT REPLACES THE BUILT-IN ENTIRELY, on the same terms as an edited prompt.
                There is no merge: a list half from the code and half from a box is a list nobody
                can read in one place, and reading it in one place is the only way anyone works out
                why a version was asked for. Saving an empty box throws the edit away. */}
            <textarea
              value={typed ?? file.text}
              onChange={(e) => setTyped(e.target.value)}
              spellCheck={false}
              rows={18}
              style={EDITOR}
              aria-label={`The bill of materials for the ${name} hop`}
            />
            <SaveRow
              onSave={() => save(typed ?? file.text)}
              busy={busy}
              said={
                said ?? (typed === null ? undefined : 'unsaved; a bump reads what is on disk')
              }
            />
          </Card>
        </section>
      )}

      <p style={NOTE}>
        {/* Said here because a reader is entitled to know how much this page can be trusted, and
            because the answer is about to change: the deterministic pinning step will apply these
            rather than measure against them. */}
        Every row is the head of a line, and it answers only for projects already on that line:
        same major, same minor, lower patch. That is why{' '}
        <code>tomcat-embed-core</code> can appear twice, once for 9.0 and once for 10.1, without the
        two competing. A project on neither is asked for nothing, because crossing from Tomcat 9 to
        Tomcat 10 is the jakarta rename and not a patch. These are hand maintained, in{' '}
        <code>src/main/resources/bom/</code>, and stated a second time as prose in{' '}
        <code>Floors.java</code>, which is what the planners read; a test binds the two.
      </p>
    </>
  )
}

const RUNGS: Style = { display: 'flex', gap: '6px', marginBottom: '14px' }

const LABEL: Style = {
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  margin: '0 0 10px',
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
