import type { ReactNode } from 'react'

export type DisclosureProps = {
  summary: ReactNode
  /** Open on first paint. For the one thing a reader came to see. */
  open?: boolean
  children: ReactNode
}

/**
 * Native `<details>`, deliberately.
 *
 * It works before hydration, it is findable by the browser's own in-page search when open, and it
 * needs no state. A hand-rolled version of this was the Java's, and it could not be searched.
 */
export function Disclosure({ summary, open = false, children }: DisclosureProps) {
  return (
    <details open={open} style={{ margin: '6px 0' }}>
      <summary
        style={{
          cursor: 'pointer',
          fontSize: '12.5px',
          color: 'var(--text-secondary)',
          padding: '3px 0',
        }}
      >
        {summary}
      </summary>
      <div style={{ padding: '6px 0 4px 14px' }}>{children}</div>
    </details>
  )
}
