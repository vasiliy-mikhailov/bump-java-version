import type { ReactNode } from 'react'
import type { Style } from './style'

export type CardProps = {
  children: ReactNode
  /** A quieter surface for something secondary, matching the sibling's nested blocks. */
  tone?: 'card' | 'subtle'
}

/**
 * A BORDERED SURFACE, which is how the sibling separates one thing from the next.
 *
 * Both tools were reaching for the same three declarations inline — a soft border, the card
 * background, six pixels of radius — and inline is where they drift. The detail pages are mostly
 * prose and code, and prose that runs edge to edge under a section label reads as one continuous
 * document; the card is what says "this block is a thing".
 */
export function Card({ children, tone = 'card' }: CardProps) {
  const style: Style = {
    border: '1px solid var(--border-soft)',
    borderRadius: '6px',
    background: tone === 'card' ? 'var(--bg-card)' : 'var(--bg-subtle)',
    padding: '12px 14px',
  }
  return <div style={style}>{children}</div>
}
