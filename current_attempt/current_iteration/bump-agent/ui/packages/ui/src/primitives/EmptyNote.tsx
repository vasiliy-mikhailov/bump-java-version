import type { ReactNode } from 'react'

export type EmptyNoteProps = { children: ReactNode }

/**
 * WHAT AN EMPTY THING SAYS, said the same way everywhere.
 *
 * An empty table that renders as nothing is indistinguishable from a table that failed to load, and
 * the reader's next move differs completely between the two. So emptiness is stated.
 */
export function EmptyNote({ children }: EmptyNoteProps) {
  return (
    <p
      style={{
        color: 'var(--text-tertiary)',
        fontSize: '12.5px',
        margin: '10px 0',
        fontStyle: 'italic',
      }}
    >
      {children}
    </p>
  )
}
