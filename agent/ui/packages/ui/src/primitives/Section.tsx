import type { ReactNode } from 'react'
import type { Style } from './style'

/**
 * THE PAGE'S GUTTER, as a padding pair.
 *
 * 24px, the same inset the header, the tally strip and every table cell use, and asserted through
 * `STRIP` in `houseStyle.test.ts`. Two tools behind one nav have to agree about where the page
 * starts; a block that chose its own inset reads as a different site the moment a reader scrolls
 * past it.
 */
export const PAGE_GUTTER = '0 24px'

/**
 * A SECTION HEADING, written down once.
 *
 * Small, uppercase, letterspaced, tertiary — the sibling tool's, and it was typed out in full in
 * four separate places, one of which was dead code. The five declarations never actually drifted,
 * which is the only reason nobody noticed; a heading style copied four times is a heading style
 * that WILL drift, and the way it drifts is that somebody adjusts the one they are looking at.
 *
 * The margin travels with it because it is part of the rhythm rather than part of the type, and it
 * is the one declaration a caller legitimately overrides: see `Section` below for the two values it
 * takes, and `prompts.tsx` for the third, where a heading sits at the top of an indented block and
 * needs no leading at all.
 */
export const HEADING: Style = {
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  margin: '18px 24px 10px',
}

export type SectionProps = {
  title: string
  /**
   * WHERE THE GUTTER IS PAID, which is the whole difference between the two shapes of section on
   * this site and the reason there were two copies of this component.
   *
   * `body` insets the whole block, and is what a section full of cards or prose wants: everything
   * inside it lines up under the heading.
   *
   * `heading` insets the heading alone and lets the body run to the edges of the page, which is
   * what a section containing a table wants: a table's own cells carry the gutter, and a table
   * inset a second time would sit 48px in while every other table on the site sits at 24.
   */
  gutter?: 'body' | 'heading'
  /**
   * An anchor, for a section something links straight to.
   *
   * It brings `scroll-margin-top` with it rather than leaving that to the caller, because a section
   * you can link to and a section that needs room above it when the browser jumps to it are the
   * same section, and the one time they were written separately the room was left off.
   */
  id?: string
  children: ReactNode
}

/**
 * A HEADING AND THE BLOCK UNDER IT. Two pages had defined this, and they had defined it differently.
 *
 * The bump page's version inset the whole section and the security page's inset only the heading,
 * and both were right for what they contained: one holds cards, the other holds full-bleed tables.
 * That difference is the `gutter` prop. Everything else about the two — the 22px trailing margin,
 * the heading treatment, the leading above it — was the same fact written twice.
 */
export function Section({ title, gutter = 'body', id, children }: SectionProps) {
  const box: Style = {
    margin: '0 0 22px',
    ...(id === undefined ? null : { scrollMarginTop: '12px' }),
    ...(gutter === 'body' ? { padding: PAGE_GUTTER } : null),
  }
  return (
    <section id={id} style={box}>
      {/* The heading's own leading is already in the block's margin when the block is inset, so it
          drops the horizontal part; when the body runs full width the heading keeps the gutter,
          because it is then the only thing on the page holding the left edge. */}
      <h2 style={gutter === 'body' ? { ...HEADING, margin: '18px 0 10px' } : HEADING}>{title}</h2>
      {children}
    </section>
  )
}
