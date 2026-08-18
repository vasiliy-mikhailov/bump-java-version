import type { ReactNode } from 'react'
import type { Style } from './style'

export type TallyProps = {
  /** A node, not a number: `40 / 356`, `6h 34m` and `—` are all legitimate values here. */
  value: ReactNode
  label: string
  /** A verdict-coloured value, for the two counts that mean better or worse. */
  tone?: 'plain' | 'good' | 'alarm'
}

const BOX: Style = {
  padding: '6px 12px',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  background: 'var(--bg-card)',
}

const VALUE: Style = { fontSize: '17px', display: 'block', fontWeight: 600 }

const LABEL: Style = { color: 'var(--text-tertiary)', fontSize: '11px' }

const TONE = {
  plain: 'var(--text-primary)',
  good: 'var(--cve-cleared)',
  alarm: 'var(--cve-introduced)',
} as const

/**
 * One count, in the strip of counts under the header.
 *
 * The shape — box, border, 17px value over an 11px tertiary label — is the sibling tool's, to the
 * pixel. Two tools behind one nav whose summary numbers are set differently look like two tools; and
 * this is the first thing on the page, so it is the first chance to look like one.
 */
export function Tally({ value, label, tone = 'plain' }: TallyProps) {
  return (
    <div style={BOX}>
      <b style={{ ...VALUE, color: TONE[tone] }}>{value}</b>
      <span style={LABEL}>{label}</span>
    </div>
  )
}

/** The strip those boxes sit in. Java's `.counts`, and the sibling's, at the same offsets. */
export const STRIP: Style = { display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '14px 24px' }
