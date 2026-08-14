'use client'

import type { ReactNode } from 'react'
import type { Style } from '@bjv/ui'
import { href } from '@/lib/api'

/**
 * THE SECTIONS OF THIS PAGE, AS THE SIBLING TOOL ARRANGES THEM.
 *
 * A ruled bar, the sections on the left and the supervisor pushed to the right — because the
 * supervisor is not a setting, it is a thing that watches the run, and putting it in the same row
 * as "the model" would invite a reader to look for a value to change in it.
 *
 * `?a=` keys the tab, which is the sibling's parameter name. Two tools whose settings pages take
 * different query parameters for the same idea are two tools; a shell that deep-links into either
 * of them should not have to remember which is which.
 */
export const TABS = [
  { a: 'prompts', label: 'prompts' },
  { a: 'run', label: 'the run' },
  { a: 'model', label: 'the model' },
  { a: 'subject', label: 'the subject' },
] as const

export type TabName = (typeof TABS)[number]['a'] | 'supervisor'

/** What each section is, in the header, so the page says what it is for before it lists values. */
export const ABOUT: Record<TabName, { title: string; subtitle: string }> = {
  prompts: { title: 'prompts', subtitle: 'what each agent is told, for the hop it is built for' },
  run: { title: 'the run', subtitle: 'how many repositories are bumped at once' },
  model: { title: 'the model', subtitle: "every value is the environment's or the code's" },
  subject: { title: 'the subject', subtitle: 'the queue this sweep is working through' },
  supervisor: { title: 'the supervisor', subtitle: 'what it sees that one bump cannot' },
}

const BAR: Style = {
  display: 'flex',
  alignItems: 'center',
  gap: '4px',
  padding: '10px 24px',
  borderBottom: '1px solid var(--border-soft)',
  background: 'var(--bg-panel)',
}

function tabStyle(current: boolean): Style {
  return {
    padding: '5px 11px',
    borderRadius: '6px',
    fontSize: '12.5px',
    textDecoration: 'none',
    whiteSpace: 'nowrap',
    color: current ? 'var(--text-primary)' : 'var(--text-tertiary)',
    background: current ? 'var(--state-selected-bg)' : 'transparent',
    fontWeight: current ? 600 : 400,
  }
}

export function SettingsTabs({ current }: { current: TabName }) {
  return (
    <nav style={BAR} aria-label="Settings sections">
      {TABS.map((t) => (
        <a key={t.a} href={href(`/settings/?a=${t.a}`)} style={tabStyle(current === t.a)}>
          {t.label}
        </a>
      ))}
      <a
        href={href('/settings/?a=supervisor')}
        style={{ ...tabStyle(current === 'supervisor'), marginLeft: 'auto' }}
      >
        the supervisor
      </a>
    </nav>
  )
}

/** The one column every section's cards sit in, at the page's gutter. */
export function Panel({ children }: { children: ReactNode }) {
  return <div style={{ padding: '18px 24px' }}>{children}</div>
}
