import type { Style } from './style'

export type ProgressBarProps = {
  /** 0-100. The caller caps it. */
  pct: number
}

const TRACK: Style = {
  height: '4px',
  margin: 0,
  background: 'var(--bg-subtle)',
  // NOT CLAMPED HERE. A caller handing this 140 has counted more settled bumps than it was given,
  // and that is worth seeing in a test rather than smoothing over in a component; `overflow` keeps
  // the mistake from breaking the page while it stays a mistake.
  overflow: 'hidden',
}

const FILL: Style = {
  display: 'block',
  height: '100%',
  background: 'linear-gradient(90deg, var(--state-bumping), var(--state-pass))',
}

/** How much of the sweep has settled. The bar directly under the header, as the sibling draws it. */
export function ProgressBar({ pct }: ProgressBarProps) {
  return (
    <div
      style={TRACK}
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label="settled"
    >
      <i style={{ ...FILL, width: `${pct}%` }} />
    </div>
  )
}
