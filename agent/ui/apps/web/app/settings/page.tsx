'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import { EmptyNote, PageHeader } from '@bjv/ui'
import { href } from '@/lib/api'
import { PromptsSection } from './prompts'
import { ModelSection, RunSection, SubjectPanel, SupervisorSection } from './sections'
import { ABOUT, Panel, SettingsTabs, type TabName } from './tabs'

const NAMES: TabName[] = ['shape', 'run', 'model', 'subject', 'supervisor']

/**
 * SETTINGS, IN THE SECTIONS THE SIBLING TOOL DIVIDES THEM INTO.
 *
 * Same tabs, same `?a=` parameter, same order, and the supervisor pushed to the right because it is
 * not a setting — it is a thing that watches the run, and a reader should not look for a value to
 * change in it.
 *
 * THE HEADER CHANGES WITH THE TAB. A page whose title stays "settings" while its body changes makes
 * the reader keep the section name in their head; the sibling titles each section and so does this.
 */
function Settings() {
  const [tab, setTab] = useState<TabName>('shape')
  const [counted, setCounted] = useState<{ n: number; edited: number } | null>(null)

  useEffect(() => {
    // ?a=prompts still lands here. The tab was called that for months and the links are in
    // people's history and in this repository's own commit messages; renaming a tab is not a
    // reason to break them.
    const asked = new URLSearchParams(window.location.search).get('a') ?? 'shape'
    // ?a=bom lands here too: the floors are on this page now, under the phases that read them.
    const a = asked === 'prompts' || asked === 'bom' ? 'shape' : asked
    setTab((NAMES as string[]).includes(a) ? (a as TabName) : 'shape')
  }, [])

  // Stable, so the prompts section's effect does not re-run on every render of this one.
  const onCount = useCallback((n: number, edited: number) => setCounted({ n, edited }), [])

  const about = ABOUT[tab]
  const subtitle =
    tab === 'shape' && counted !== null
      ? `${counted.n} agent(s) · ` +
        (counted.edited === 0
          ? "none edited, all are the code's own"
          : `${counted.edited} edited, the rest are the code's`)
      : about.subtitle

  return (
    <>
      <PageHeader title={about.title} subtitle={subtitle} back={{ label: 'bumps', href: href('/') }} />
      <SettingsTabs current={tab} />
      <Panel>
        {tab === 'shape' ? <PromptsSection onCount={onCount} /> : null}
        {tab === 'run' ? <RunSection /> : null}
        {tab === 'model' ? <ModelSection /> : null}
        {tab === 'subject' ? <SubjectPanel /> : null}
        {tab === 'supervisor' ? <SupervisorSection /> : null}
      </Panel>
    </>
  )
}

export default function Page() {
  return (
    <Suspense fallback={<EmptyNote>Reading the settings…</EmptyNote>}>
      <Settings />
    </Suspense>
  )
}
