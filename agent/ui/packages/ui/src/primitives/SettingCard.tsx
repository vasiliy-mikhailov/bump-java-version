import type { ReactNode } from 'react'
import type { Style } from './style'

export type SettingCardProps = {
  title: string
  /** Where the value comes from: "currently 4", "the environment's", "the code's own". */
  provenance?: string
  /** What this setting does, in prose, at reading size. */
  children: ReactNode
  /** The note under the card: when a change takes effect, and what it does not do. */
  footnote?: ReactNode
}

const CARD: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '14px 16px',
}

const TITLE: Style = { fontWeight: 600, fontSize: '13px' }

const PROVENANCE: Style = {
  fontSize: '10px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  marginLeft: '8px',
}

const FOOT: Style = {
  fontSize: '11.5px',
  lineHeight: 1.6,
  color: 'var(--text-tertiary)',
  margin: '8px 0 0',
  maxWidth: '72ch',
}

/**
 * ONE SETTING, IN THE CARD THE SIBLING TOOL SHOWS ONE IN.
 *
 * The provenance is the part worth copying deliberately. A value on a settings page is ambiguous
 * until you know whether it came from the environment, from the code's default, or from somebody
 * typing it here — and the reader's next action differs for all three. Saying it in the heading
 * costs a word and removes the question.
 */
export function SettingCard({ title, provenance, children, footnote }: SettingCardProps) {
  return (
    <>
      <section style={CARD}>
        <h3 style={{ margin: '0 0 8px' }}>
          <span style={TITLE}>{title}</span>
          {provenance === undefined ? null : <span style={PROVENANCE}>{provenance}</span>}
        </h3>
        {children}
      </section>
      {footnote === undefined ? null : <p style={FOOT}>{footnote}</p>}
    </>
  )
}
