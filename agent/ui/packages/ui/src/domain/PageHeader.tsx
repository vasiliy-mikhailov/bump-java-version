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

/**
 * THE BUTTON TWIN OF THE CORNER GEAR.
 *
 * It spreads CORNER rather than restating its numbers, so every corner control shares one box by
 * construction and they cannot drift apart the next time one of them is adjusted. A button does not
 * inherit fontFamily, and the `font` shorthand would take CORNER's fontSize down with it, so the
 * family is named on a line of its own.
 *
 * The border is reserved transparent and paid for out of the padding: a refusal can turn it red
 * without moving the gear beside it by a pixel. No hover, because the gear has none and matching
 * that corner is the whole argument for this shape.
 *
 * It lives here beside CORNER rather than in the page that first needed it, because there are two
 * such buttons now and a second copy of these numbers is how the corner stops looking like one row.
 */
export const CORNER_BUTTON: Style = {
  ...CORNER,
  display: 'inline-flex',
  alignItems: 'center',
  appearance: 'none',
  background: 'none',
  border: '1px solid transparent',
  padding: '0 calc(0.35rem - 1px)',
  margin: 0,
  fontFamily: 'inherit',
  cursor: 'pointer',
  transition: 'color 120ms ease, border-color 120ms ease',
}

/** An ask is in flight. Dimmed rather than removed, so the row does not reflow under the pointer. */
export const CORNER_BUSY: Style = { opacity: 0.55, cursor: 'progress' }

/** The last ask was refused. Reserved border, so this costs no layout. */
export const CORNER_REFUSED: Style = { color: 'var(--danger)', borderColor: 'var(--danger)' }

/**
 * 1.25em, MEASURED OFF THE RENDERED PAGE RATHER THAN REASONED ABOUT.
 *
 * The gear these sit beside is emoji-presented: U+2699 with no variation selector falls through to
 * the colour emoji face, which is why it is blue in a monochrome header, and an emoji glyph
 * overshoots its em. Measured on the real page at 1.25rem, the gear's ink is 22.2px square while a
 * 1em drawing came out at 12. Guessing from a text face said the opposite, which is why this number
 * is taken from a screenshot of the thing itself.
 *
 * 1.25em puts a drawn mark at about 85 per cent of the gear's diameter, where a stroked mark reads
 * as the same weight as a filled one rather than as a larger, thinner ring. If the gear ever loses
 * its emoji presentation this drops back to roughly 0.8em, so re-measure rather than trusting the
 * number. Vertical padding is nil because the drawing is already taller than the glyph's line box.
 */
export const CORNER_MARK: Style = { width: '1.25em', height: '1.25em', display: 'block' }
