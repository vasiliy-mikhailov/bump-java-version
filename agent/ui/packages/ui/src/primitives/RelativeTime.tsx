export type RelativeTimeProps = { at: number; now?: number }

/**
 * HOW LONG AGO, computed from a caller-supplied `now` by default absent.
 *
 * The `now` prop exists so this is testable without freezing a clock globally, and so a table of
 * fifty rows shares one reading rather than taking fifty that disagree by milliseconds.
 *
 * THIS ONE STAYS IN THIS REPOSITORY, and the file it used to share with `duration` is the reason it
 * is worth saying why. The sibling tool has a "how long ago" too and the two do not converge: this
 * crosses into minutes at sixty seconds and rounds, that one crosses at ninety and floors, it has a
 * day rung this reaches at forty-eight hours, its version is a component with a timer inside it that
 * slows down as the number does, and it has two variants where 0 means "nothing yet" in one and an
 * epoch in the other. Two products, not one written twice. `ratchet-ui`'s `time.ts` carries the same
 * note, so that nobody spends an afternoon trying again.
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
 * A DURATION IS NOT A TIME AGO, AND ONLY ONE OF THE TWO TRAVELLED.
 *
 * `relative` says "3h ago"; `duration` says "3h 4m", which is what a reader wants of a bump still
 * running, of a sweep's elapsed time, and of an eta that has not happened. Both dashboards had
 * written the second one and their two versions differed only in the rounding, so it is now
 * `ratchet-ui/time`, which reaches no React at all and can therefore be used by a test or a server
 * log line as well as by a cell. Re-exported from here so that no call site had to move.
 */
export { duration, spellMinutes } from 'ratchet-ui/time'

export function RelativeTime({ at, now = Date.now() }: RelativeTimeProps) {
  return (
    <time dateTime={new Date(at).toISOString()} style={{ color: 'var(--text-tertiary)' }}>
      {relative(at, now)}
    </time>
  )
}
