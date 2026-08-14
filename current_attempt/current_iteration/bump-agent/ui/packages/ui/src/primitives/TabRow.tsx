export type TabItem = { label: string; href: string; current: boolean }

export type TabRowProps = { tabs: TabItem[]; label: string }

/**
 * TABS TAKE HREFS, NOT A DOMAIN KEY.
 *
 * The Java had three of these — one per page that wanted tabs — because each reached for its own
 * page's URLs. The three drifted, and only one of them ever got the fix for the tab that stayed lit
 * after you left it.
 */
export function TabRow({ tabs, label }: TabRowProps) {
  return (
    <nav
      aria-label={label}
      style={{
        display: 'flex',
        gap: '4px',
        borderBottom: '1px solid var(--border-soft)',
        margin: '0 0 16px',
      }}
    >
      {tabs.map((t) => (
        <a
          key={t.href}
          href={t.href}
          aria-current={t.current ? 'page' : undefined}
          style={{
            padding: '7px 13px',
            fontSize: '13px',
            textDecoration: 'none',
            color: t.current ? 'var(--text-primary)' : 'var(--text-tertiary)',
            borderBottom: `2px solid ${t.current ? 'var(--accent-primary)' : 'transparent'}`,
            marginBottom: '-1px',
          }}
        >
          {t.label}
        </a>
      ))}
    </nav>
  )
}
