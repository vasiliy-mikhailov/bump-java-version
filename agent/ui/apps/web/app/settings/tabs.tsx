'use client'

import type { ReactNode } from 'react'
import { SectionTabs } from '@bjv/ui'
import { href } from '@/lib/api'

/**
 * THE SECTIONS OF THIS PAGE, AS THE SIBLING TOOL ARRANGES THEM.
 *
 * A ruled bar, the sections on the left and the supervisor pushed to the right, because the
 * supervisor is not a setting, it is a thing that watches the run, and putting it in the same row
 * as "the model" would invite a reader to look for a value to change in it.
 *
 * `?a=` keys the tab, which is the sibling's parameter name. Two tools whose settings pages take
 * different query parameters for the same idea are two tools; a shell that deep-links into either
 * of them should not have to remember which is which.
 *
 * THE BAR ITSELF IS NOW `SectionTabs` IN `ratchet-ui`, and the two files that met in it had never
 * seen each other: the tab inset, the radius, the token under the current tab, the bar's own inset
 * and its bottom rule were identical declarations in both, and both had written the trailing
 * departure with an auto left margin and the same argument for it in words. What stayed here is
 * everything that is about THIS tool: the routes, the words on the tabs, and the copy under them.
 */
export const TABS = [
  // THE SHAPE, NOT THE PROMPTS. A list of thirty-four prompts is an inventory; what a reader
  // needs is the program they sit in, with what runs per module, what only runs on a red
  // gate, and which list each pin phase works to. The prompts are still here, under the
  // stage that runs them, which is the only place they mean anything.
  { a: 'shape', label: 'the shape' },
  { a: 'run', label: 'the run' },
  { a: 'model', label: 'the model' },
  { a: 'subject', label: 'the subject' },
] as const

export type TabName = (typeof TABS)[number]['a'] | 'supervisor'

/** What each section is, in the header, so the page says what it is for before it lists values. */
export const ABOUT: Record<TabName, { title: string; subtitle: string }> = {
  shape: { title: 'the shape', subtitle: 'the program a bump runs, and what every agent in it is told' },
  run: { title: 'the run', subtitle: 'how many repositories are bumped at once' },
  model: { title: 'the model', subtitle: "every value is the environment's or the code's" },
  subject: { title: 'the subject', subtitle: 'the queue this sweep is working through' },
  supervisor: { title: 'the supervisor', subtitle: 'what it sees that one bump cannot' },
}

export function SettingsTabs({ current }: { current: TabName }) {
  return (
    <SectionTabs
      label="Settings sections"
      tabs={TABS.map((t) => ({
        href: href(`/settings/?a=${t.a}`),
        label: t.label,
        current: current === t.a,
      }))}
      // ONE DEPARTURE, AND IT IS LIT WHEN THE READER IS ON IT. The sibling's version of this bar
      // never lights a departure, on the grounds that lighting one claims the reader is already
      // there. That is right for a link that leaves the page; the supervisor is a section of this
      // page wearing a divider, so it is lit like any other.
      trailing={[
        {
          href: href('/settings/?a=supervisor'),
          label: 'the supervisor',
          current: current === 'supervisor',
        },
      ]}
    />
  )
}

/** The one column every section's cards sit in, at the page's gutter. */
export function Panel({ children }: { children: ReactNode }) {
  return <div style={{ padding: '18px 24px' }}>{children}</div>
}
