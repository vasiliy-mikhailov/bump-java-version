'use client'

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FIELD, LabeledField } from './LabeledField'
import type { Style } from './style'

export type SecretFieldProps = {
  label: string
  value: string
  onChange: (v: string) => void
  /** Under the control, like every other field here: what a wrong value does. */
  hint?: ReactNode
}

const ROW: Style = { display: 'flex', gap: '6px', alignItems: 'center', maxWidth: '540px' }

const ICON: Style = {
  background: 'var(--bg-panel)',
  border: '1px solid var(--border-strong)',
  borderRadius: '6px',
  padding: '7px 10px',
  cursor: 'pointer',
  font: 'inherit',
  lineHeight: 1,
  color: 'var(--text-secondary)',
}

const ICON_OFF: Style = { ...ICON, cursor: 'not-allowed', opacity: 0.45 }

/**
 * A MASKED VALUE WITH THE TWO BUTTONS THAT MAKE A MASKED VALUE USABLE.
 *
 * THE NAME AND THE BEHAVIOUR ARE BOTH THE SIBLING'S, which is the first time that has happened in
 * this direction. `fix-java-svace-markers` has had this component for as long as its settings page
 * has shown a key; this repository declined to show one and so had nothing to share. Now that the
 * decision is reversed (see the Java's `Settings.model`, which records why), writing a second
 * version of a component that already exists twenty metres away would be how two tools behind one
 * nav start looking like two products. It is a copy rather than an import because `ratchet-ui`
 * 0.4.0 does not carry it; it is the obvious candidate for the next tranche.
 *
 * WHAT IS OURS IS THE FRAME. The label and hint come from `LabeledField` rather than from private
 * metrics, so this field is the same shape as every other field on the settings page. The sibling's
 * own version carries its own label markup, and taking that too would have imported the sibling's
 * field metrics into the middle of one of our cards.
 *
 * WHAT BLANK MEANS IS NOT THIS COMPONENT'S TO DECIDE and the two pages genuinely disagree. The
 * sibling leaves a blank box alone, because a browser that clears the field must not silently unset
 * the key; this page refuses a blank save outright, and says so in the hint. Blank policy is the
 * form's contract, stated per screen, or a shared field teaches a rule that is true on one page
 * only.
 *
 * THE SECRET IS IN THE PAGE, and that is the price of reveal and copy. It is why this dashboard
 * sits behind basic auth. A screen that does not need the buttons should drop the value from its
 * payload instead, and the secret stops leaving the box at all.
 */
export function SecretField({ label, value, onChange, hint }: SecretFieldProps) {
  const input = useRef<HTMLInputElement>(null)
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState(false)
  const [canCopy, setCanCopy] = useState(false)

  // `navigator.clipboard` exists only in a secure context, so copy is dead over plain http, and a
  // button that does nothing when clicked is worse than no button. Read after mount: these pages
  // are a static export, so there is no window when the HTML is written and the honest first render
  // is the disabled one.
  useEffect(() => {
    setCanCopy(typeof navigator !== 'undefined' && navigator.clipboard !== undefined)
  }, [])

  useEffect(() => {
    if (!copied) {
      return
    }
    const timer = setTimeout(() => setCopied(false), 1200)
    return () => clearTimeout(timer)
  }, [copied])

  const copy = () => {
    const field = input.current
    if (field === null || !canCopy) {
      return
    }
    void navigator.clipboard.writeText(field.value).then(() => setCopied(true))
  }

  return (
    <LabeledField label={label} hint={hint}>
      <span style={ROW}>
        <input
          ref={input}
          aria-label={label}
          // MASKED UNTIL ASKED FOR. The field is a credential and this page is read over a
          // shoulder and screenshotted; `password` is what keeps it out of both by default.
          type={revealed ? 'text' : 'password'}
          // A CREDENTIAL IS NOT FOR THE BROWSER'S KEYCHAIN. Offering to remember it puts a copy
          // somewhere this page cannot reach and nobody will think to clear.
          autoComplete="off"
          spellCheck={false}
          value={value}
          onChange={(e) => onChange(e.currentTarget.value)}
          style={FIELD}
        />
        <button
          type="button"
          onClick={() => setRevealed((was) => !was)}
          style={ICON}
          title={revealed ? 'hide' : 'show'}
          aria-pressed={revealed}
        >
          {revealed ? 'hide' : 'show'}
        </button>
        <button
          type="button"
          onClick={copy}
          disabled={!canCopy}
          style={canCopy ? ICON : ICON_OFF}
          title={canCopy ? 'copy' : 'copying needs https'}
        >
          {copied ? 'copied' : 'copy'}
        </button>
      </span>
    </LabeledField>
  )
}
