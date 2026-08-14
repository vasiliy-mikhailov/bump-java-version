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

/**
 * A DURATION, NOT A TIME AGO. `relative` says "3h ago"; this says "3h 4m", which is what a reader
 * wants of a bump still running, of a sweep's elapsed time, and of an eta that has not happened.
 *
 * Seconds survive below an hour because that is the range where they carry information: "8m 45s"
 * against "9m" is the difference between a fast bump and a rounded one. Above an hour they are
 * noise and the minutes are what matters.
 */
export function duration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 === 0 ? `${m}m` : `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return m % 60 === 0 ? `${h}h` : `${h}h ${m % 60}m`
}

/** The estimator speaks in whole minutes, so its unit is the one that comes back out. */
export function spellMinutes(minutes: number): string {
  return duration(minutes * 60_000)
}

export function RelativeTime({ at, now = Date.now() }: RelativeTimeProps) {
  return (
    <time dateTime={new Date(at).toISOString()} style={{ color: 'var(--text-tertiary)' }}>
      {relative(at, now)}
    </time>
  )
}
