'use client'

import { CORNER } from '@bjv/ui'
import { href } from '@/lib/api'

/**
 * THE CORNER GEAR, which is NOT the shell's nav.
 *
 * The shell reads `nav` from the manifest and draws the top-level entries. This was a second row of
 * tabs under them, which meant a mounted zone wore two rows of chrome disagreeing about which was
 * in charge, and spent a whole line above the fold on a single destination. Settings is one
 * destination and it gets one glyph, in the corner, where the sibling tool keeps its own. The way
 * back is the header's crumb, which the settings page already draws.
 */
export function Nav({ current }: { current: string }) {
  return (
    <a
      href={href('/settings/')}
      style={CORNER}
      aria-label="Settings"
      title="Settings"
      aria-current={current === 'settings' ? 'page' : undefined}
    >
      {'\u2699'}
    </a>
  )
}
