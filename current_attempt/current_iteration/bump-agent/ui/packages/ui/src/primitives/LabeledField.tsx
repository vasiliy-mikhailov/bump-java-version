import type { ReactNode } from 'react'
import type { Style } from './style'

export type LabeledFieldProps = {
  label: string
  /** Under the control, not beside it: what the value means and what a wrong one does. */
  hint?: ReactNode
  children: ReactNode
}

const LABEL: Style = {
  display: 'block',
  fontSize: '11.5px',
  color: 'var(--text-tertiary)',
  margin: '0 0 4px',
}

const HINT: Style = {
  fontSize: '11.5px',
  lineHeight: 1.6,
  color: 'var(--text-tertiary)',
  margin: '5px 0 0',
  maxWidth: '72ch',
}

/** A control with its name above it and its consequences under it. */
export function LabeledField({ label, hint, children }: LabeledFieldProps) {
  return (
    <div style={{ margin: '0 0 14px' }}>
      <label style={LABEL}>{label}</label>
      {children}
      {hint === undefined ? null : <p style={HINT}>{hint}</p>}
    </div>
  )
}

/** The input shape every field on the page wears, at the sibling's metrics. */
export const FIELD: Style = {
  width: '100%',
  maxWidth: '540px',
  padding: '7px 10px',
  borderRadius: '6px',
  border: '1px solid var(--border-strong)',
  background: 'var(--bg-panel)',
  color: 'var(--text-primary)',
  font: 'inherit',
}

/** A value this page reads and cannot set: same shape, visibly not a control. */
export const READONLY: Style = {
  ...FIELD,
  background: 'var(--bg-subtle)',
  color: 'var(--text-secondary)',
}
