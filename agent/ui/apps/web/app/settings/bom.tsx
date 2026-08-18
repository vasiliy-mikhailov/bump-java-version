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
/**
 * ONE LIST, UNDER THE STAGE THAT WORKS TO IT.
 *
 * It had a tab of its own, which meant a reader looking at before-pins had to remember which half
 * of the bill of materials it read, go somewhere else, and come back. The stage names the list and
 * the list is right there now; the two facts were never separable and the tab was making them look
 * like they were.
 */
export function BomFile({
  file,
  typed,
  busy,
  said,
  onType,
  onSave,
}: {
  file: File
  typed: string | undefined
  busy: boolean
  said: string | undefined
  onType: (text: string) => void
  onSave: (text: string) => void
}) {
  return (
    <section style={{ margin: '10px 0 4px' }}>
      <p style={ABOUT}>
        {file.about}
        {file.edited ? <span style={EDITED}>edited</span> : null}
      </p>
      <Card>
        {/* AN EDIT REPLACES THE BUILT-IN ENTIRELY, on the same terms as an edited prompt. No
            merge: a list half from the code and half from a box is a list nobody can read in one
            place, and reading it in one place is the only way anyone works out why a version was
            asked for. Saving an empty box throws the edit away; the built-in was never gone. */}
        <textarea
          value={typed ?? file.text}
          onChange={(e) => onType(e.target.value)}
          spellCheck={false}
          rows={14}
          style={EDITOR}
          aria-label={file.title}
        />
        <SaveRow
          onSave={() => onSave(typed ?? file.text)}
          busy={busy}
          said={
            said !== undefined && said !== ''
              ? said
              : typed === undefined
                ? undefined
                : 'unsaved; a bump reads what is on disk when it starts'
          }
        />
      </Card>
    </section>
  )
}

export type { Bom, File }

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
