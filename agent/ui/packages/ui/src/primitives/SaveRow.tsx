import type { ReactNode } from 'react'
import type { Style } from './style'

export type SaveRowProps = {
  onSave: () => void
  /** Disabled while a save is in flight, so a second click cannot race the first. */
  busy?: boolean
  /** What the server said afterwards, which is not what was typed. */
  said?: ReactNode
}

const SAVE: Style = {
  padding: '7px 16px',
  borderRadius: '6px',
  border: 0,
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  font: 'inherit',
  fontWeight: 600,
  cursor: 'pointer',
}

/** The accent-coloured save, and room beside it for what the server kept. */
export function SaveRow({ onSave, busy = false, said }: SaveRowProps) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
      <button type="button" onClick={onSave} disabled={busy} style={{ ...SAVE, opacity: busy ? 0.6 : 1 }}>
        save
      </button>
      {said === undefined ? null : (
        <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{said}</span>
      )}
    </div>
  )
}
