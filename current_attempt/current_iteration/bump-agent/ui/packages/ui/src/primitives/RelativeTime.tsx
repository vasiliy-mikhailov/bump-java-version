export type RelativeTimeProps = { at: number; now?: number }

/**
 * HOW LONG AGO, computed from a caller-supplied `now` by default absent.
 *
 * The `now` prop exists so this is testable without freezing a clock globally, and so a table of
 * fifty rows shares one reading rather than taking fifty that disagree by milliseconds.
 */
export function relative(at: number, now: number): string {
  const s = Math.max(0, Math.round((now - at) / 1000))
  if (s < 60) return `${s}s ago`
  const m = Math.round(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 48) return `${h}h ago`
  return `${Math.round(h / 24)}d ago`
}

export function RelativeTime({ at, now = Date.now() }: RelativeTimeProps) {
  return (
    <time dateTime={new Date(at).toISOString()} style={{ color: 'var(--text-tertiary)' }}>
      {relative(at, now)}
    </time>
  )
}
