'use client'

import { useState } from 'react'

export type TextFoldProps = {
  text: string
  /** Lines shown before folding. Below this the control is not drawn at all. */
  lines?: number
}

/**
 * A LONG BODY, FOLDED HERE RATHER THAN TRUNCATED ON THE SERVER.
 *
 * The Java cut trace bodies to a few hundred characters before sending them, which is the one
 * decision that cannot be undone by the reader: the evidence for why a bump failed was routinely in
 * the part that was cut. The whole text travels; the page decides how much to show.
 */
export function TextFold({ text, lines = 12 }: TextFoldProps) {
  const [open, setOpen] = useState(false)
  const all = text.split('\n')
  const long = all.length > lines
  const shown = open || !long ? text : all.slice(0, lines).join('\n')
  return (
    <div>
      <pre
        style={{
          margin: 0,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
          fontSize: '12px',
          lineHeight: 1.5,
          color: 'var(--text-secondary)',
        }}
      >
        {shown}
      </pre>
      {long ? (
        <button
          type="button"
          onClick={() => setOpen(!open)}
          style={{
            marginTop: '6px',
            padding: 0,
            border: 0,
            background: 'none',
            color: 'var(--accent-primary)',
            fontSize: '12px',
            cursor: 'pointer',
          }}
        >
          {open ? 'show less' : `show all ${all.length} lines`}
        </button>
      ) : null}
    </div>
  )
}
