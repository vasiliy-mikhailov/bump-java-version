export type CodeBlockProps = { children: string }

/** Monospace, scrollable in its own box. Wide content never makes the page scroll sideways. */
export function CodeBlock({ children }: CodeBlockProps) {
  return (
    <pre
      style={{
        margin: '6px 0',
        padding: '10px 12px',
        overflowX: 'auto',
        borderRadius: '6px',
        background: 'var(--bg-subtle)',
        border: '1px solid var(--border-soft)',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
        fontSize: '12px',
        lineHeight: 1.5,
        color: 'var(--text-secondary)',
      }}
    >
      {children}
    </pre>
  )
}
