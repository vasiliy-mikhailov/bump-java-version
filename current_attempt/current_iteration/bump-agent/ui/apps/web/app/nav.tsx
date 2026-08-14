'use client'

import { TabRow } from '@bjv/ui'
import { href } from '@/lib/api'

/**
 * The zone's own tabs, which are NOT the shell's nav.
 *
 * The shell reads `nav` from the manifest and draws the top-level entries; these are the pages
 * within this tool that a reader moves between while staying in it. Keeping both means a mounted
 * zone has one row of chrome, not two competing ones.
 */
export function Nav({ current }: { current: string }) {
  return (
    <div style={{ padding: '0 24px' }}>
    <TabRow
      label="Sections of this tool"
      tabs={[
        { label: 'bumps', href: href('/'), current: current === 'bumps' },
        { label: 'settings', href: href('/settings/'), current: current === 'settings' },
      ]}
    />
    </div>
  )
}
