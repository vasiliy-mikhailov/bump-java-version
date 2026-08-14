import type { ReactNode } from 'react'
import type { Style } from './style'

/**
 * SIX TONES, AND NOT ONE OF THEM IS A VERDICT.
 *
 * A `Pill` does not know what `FAIL_test_conservation` means. `VerdictPill` and `RolePill` map their
 * own vocabulary onto these; nothing else may pass a tone straight through from a payload, because a
 * tone arriving over the wire is a colour decision made by a server that cannot be tested for it.
 */
export type PillTone = 'good' | 'warn' | 'quiet' | 'alarm' | 'running' | 'aside'

export type PillProps = {
  tone: PillTone
  href?: string
  title?: string
  children: ReactNode
}

/**
 * Tone → token. The names on the right are this domain's, from `domain.css`; the names on the left
 * are what a pill is FOR, and the gap between the two columns is the only place a colour lives.
 */
const TONE: Record<PillTone, string> = {
  good: 'var(--state-pass)',
  warn: 'var(--state-fail-target-not-bumped)',
  quiet: 'var(--state-no-baseline)',
  alarm: 'var(--state-fail-test-conservation)',
  running: 'var(--state-bumping)',
  aside: 'var(--state-blocked-dependency)',
}

function pillStyle(tone: PillTone): Style {
  return {
    '--pill-tone': TONE[tone],
    display: 'inline-block',
    padding: '2px 9px',
    borderRadius: '20px',
    fontSize: '11px',
    whiteSpace: 'nowrap',
    textDecoration: 'none',
    color: 'var(--pill-tone)',
    background: 'color-mix(in srgb, var(--pill-tone) 14%, transparent)',
    border: '1px solid color-mix(in srgb, var(--pill-tone) 32%, transparent)',
  }
}

/**
 * The pill every verdict, role and count is shown in. The one purely presentational primitive.
 *
 * `running` keeps a pulsing dot, because a page that is live has to say which row is MOVING and a
 * static blue pill does not. The portal's `prefers-reduced-motion` rule switches it off for readers
 * who asked.
 */
export function Pill({ tone, href, title, children }: PillProps) {
  const body = (
    <>
      {tone === 'running' ? (
        <span className="animate-pulse" aria-hidden="true">
          {'● '}
        </span>
      ) : null}
      {children}
    </>
  )
  if (href === undefined) {
    return (
      <span style={pillStyle(tone)} title={title}>
        {body}
      </span>
    )
  }
  return (
    <a href={href} style={pillStyle(tone)} title={title}>
      {body}
    </a>
  )
}
