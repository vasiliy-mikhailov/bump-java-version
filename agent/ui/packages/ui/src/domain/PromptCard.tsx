'use client'

import { useEffect, useState } from 'react'
import type { AgentPrompt } from '@bjv/types'
import type { Style } from '../primitives/style'

export type PromptCardProps = {
  prompt: AgentPrompt
  /** Absent renders the card read-only, which is what a page without a store should do. */
  onSave?: (text: string) => Promise<void>
  onRevert?: () => Promise<void>
}

const CARD: Style = {
  border: '1px solid var(--border-soft)',
  borderRadius: '8px',
  background: 'var(--bg-card)',
  padding: '12px 14px',
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  minWidth: 0,
}

const NAME: Style = { fontWeight: 600, fontSize: '13px', color: 'var(--text-primary)' }

const PROVENANCE: Style = {
  fontSize: '10px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
}

const BODY: Style = {
  margin: 0,
  padding: '10px 12px',
  minHeight: '190px',
  maxHeight: '320px',
  width: '100%',
  boxSizing: 'border-box',
  resize: 'vertical',
  border: '1px solid var(--border-soft)',
  borderRadius: '6px',
  background: 'var(--bg-panel)',
  font: 'inherit',
  fontSize: '12px',
  lineHeight: 1.5,
  color: 'var(--text-secondary)',
}

const SAVE: Style = {
  padding: '6px 15px',
  borderRadius: '6px',
  border: 0,
  background: 'var(--accent-action)',
  color: 'var(--accent-on-action)',
  font: 'inherit',
  fontWeight: 600,
  cursor: 'pointer',
}

const REVERT: Style = {
  padding: '6px 15px',
  borderRadius: '6px',
  border: '1px solid var(--border-strong)',
  background: 'transparent',
  color: 'var(--text-secondary)',
  font: 'inherit',
  cursor: 'pointer',
}

const ROLE: Record<string, string> = {
  planner: 'var(--role-planner)',
  doer: 'var(--role-doer)',
  verifier: 'var(--role-verifier)',
}

/**
 * ONE AGENT'S PROMPT, EDITABLE.
 *
 * AN EDIT REPLACES THE BUILT-IN ENTIRELY. There is no merge, because a prompt half from the code and
 * half from a box is a prompt nobody can read in one place — and reading it in one place is how
 * anybody works out why an agent did what it did.
 *
 * REVERT DELETES THE EDIT; it does not restore anything, because the built-in was never gone. That
 * is why the button is offered only when there is an edit to throw away: a revert on an unedited
 * prompt would be a control that does nothing, which reads as a control that is broken.
 *
 * THE ROLE IS THE STRIPE. A reader scanning a stage wants to know which of the three they are
 * looking at before reading a word, and the colour answers that where the suffix requires reading to
 * the end of the name.
 */
export function PromptCard({ prompt, onSave, onRevert }: PromptCardProps) {
  const [text, setText] = useState(prompt.prompt)
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<string | null>(null)

  // The server is the authority on what is in force. After a save or a revert the parent refetches,
  // and this follows rather than keeping whatever was typed.
  useEffect(() => {
    setText(prompt.prompt)
  }, [prompt.prompt])

  const dirty = text !== prompt.prompt
  const editable = onSave !== undefined

  const run = (what: () => Promise<void>, done: string) => {
    setBusy(true)
    setSaid(null)
    what()
      .then(() => setSaid(done))
      .catch((e: Error) => setSaid(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <article
      style={{ ...CARD, borderLeft: `3px solid ${ROLE[prompt.role] ?? 'var(--border-strong)'}` }}
    >
      <header style={{ display: 'flex', alignItems: 'baseline', gap: '8px', flexWrap: 'wrap' }}>
        <span style={NAME}>{prompt.name}</span>
        <span style={PROVENANCE}>{prompt.edited ? 'edited' : "the code's own"}</span>
        {dirty ? (
          <span style={{ ...PROVENANCE, color: 'var(--verdict-again)' }}>unsaved</span>
        ) : null}
      </header>
      <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-tertiary)' }}>
        {prompt.description}
      </p>
      <textarea
        style={BODY}
        value={text}
        readOnly={!editable}
        spellCheck={false}
        onChange={(e) => setText(e.target.value)}
      />
      {editable ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <button
            type="button"
            disabled={busy || !dirty}
            onClick={() => run(() => onSave(text), 'saved')}
            style={{ ...SAVE, opacity: busy || !dirty ? 0.5 : 1 }}
          >
            save
          </button>
          {prompt.edited && onRevert !== undefined ? (
            <button
              type="button"
              disabled={busy}
              onClick={() => run(onRevert, 'reverted')}
              style={{ ...REVERT, opacity: busy ? 0.5 : 1 }}
            >
              revert
            </button>
          ) : null}
          {said === null ? null : (
            <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{said}</span>
          )}
        </div>
      ) : null}
    </article>
  )
}

/** The two-column grid the cards sit in, collapsing to one on a narrow viewport. */
export const CARDS: Style = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))',
  gap: '14px',
}
