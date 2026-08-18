import type { ReactNode } from 'react'
import type { Style } from '../primitives/style'

/** Somewhere to go back to. A label is not a destination, so both travel. */
export type Crumb = { label: string; href: string }

export type PageHeaderProps = {
  title: string
  /**
   * A NODE, NOT A STRING. Screens compose entities and pills into their subtitle — a verdict beside
   * a repository name, a hop beside a sha — and a `string` prop forces the caller to either flatten
   * that or reach for markup.
   */
  subtitle: ReactNode
  back?: Crumb
  /** The corner controls, in a row rather than at three hand-measured offsets. */
  actions?: ReactNode
}

const HEADER: Style = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: '12px',
  padding: '16px 24px',
  borderBottom: '1px solid var(--border-soft)',
}

const TITLE: Style = { margin: 0, fontSize: '14px', fontWeight: 600 }
const SUB: Style = { color: 'var(--text-tertiary)', fontSize: '12px', marginTop: '3px' }
const CRUMB: Style = {
  display: 'inline-block',
  marginBottom: '6px',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
}
const ACTIONS: Style = { marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '2px' }

/**
 * The header every screen wears, at the sibling tool's measurements.
 *
 * FULL BLEED WITH A RULE UNDER IT, not a centred column. Two tools behind one nav must agree about
 * where the page starts, and a zone that insets its content by a different amount reads as a
 * different site the moment a reader crosses the boundary.
 *
 * There is no product name and no logo: mounted in a shell, the shell already said which tool this
 * is, and a zone that repeats it spends the one line above the fold saying nothing.
 */
export function PageHeader({ title, subtitle, back, actions }: PageHeaderProps) {
  return (
    <header style={HEADER}>
      <div>
        {back === undefined ? null : (
          <a href={back.href} style={CRUMB}>
            {'← '}
            {back.label}
          </a>
        )}
        <h1 style={TITLE}>{title}</h1>
        <div style={SUB}>{subtitle}</div>
      </div>
      {actions === undefined ? null : <div style={ACTIONS}>{actions}</div>}
    </header>
  )
}

/** The gear-shaped corner link, at the sibling's metrics so the two corners match. */
export const CORNER: Style = {
  fontSize: '1.25rem',
  lineHeight: 1,
  color: 'var(--text-tertiary)',
  textDecoration: 'none',
  padding: '0.2rem 0.35rem',
  borderRadius: '5px',
}
