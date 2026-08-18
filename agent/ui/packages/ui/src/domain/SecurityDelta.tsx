import { Tally } from '../primitives/Tally'

export type SecurityDeltaProps = {
  before: number
  /** Null when no after scan was taken, which is every bump that did not reach a green gate. */
  after: number | null
  distinctBefore: number
  distinctAfter: number | null
}

/**
 * CRITICAL+HIGH, BEFORE AND AFTER — and the honest number beside the headline one.
 *
 * The scan reports a finding once per module that resolves the dependency, so a seventeen-module
 * project counts the same CVE seventeen times. Measured across this corpus the inflation is 1.67x
 * overall and 15.5x at the extreme, which makes the headline count incomparable between repositories:
 * ranking by it ranks by module count.
 *
 * Both numbers are shown rather than one being silently corrected. The occurrence count is what
 * every number this corpus has already reported was measured in, so replacing it would make new runs
 * incomparable with old ones; the distinct count is what a reader actually means by "how many
 * vulnerabilities". Showing one without the other has been wrong in both directions.
 */
export function SecurityDelta({ before, after, distinctBefore, distinctAfter }: SecurityDeltaProps) {
  // AN AFTER THAT WAS NEVER TAKEN IS NOT ZERO, and the difference matters more here than anywhere
  // else on the page. The after scan runs only on a green gate; treating its absence as zero put
  // "337 -> 0, cleared 337" above the dependency table of a bump that had cleared nothing.
  const moved = after === null ? null : before - after
  const tone = moved === null ? 'plain' : moved > 0 ? 'good' : moved < 0 ? 'alarm' : 'plain'
  return (
    <div style={{ display: 'flex', gap: '26px', flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <Tally label="CRITICAL+HIGH before" value={before} />
      <Tally label={after === null ? 'after, not measured' : 'after'} value={after ?? '—'} tone={tone} />
      <Tally
        label={moved === null ? 'cleared, not measured' : moved >= 0 ? 'cleared' : 'introduced'}
        value={moved === null ? '—' : Math.abs(moved)}
        tone={tone}
      />
      <Tally label="distinct before" value={distinctBefore} />
      <Tally label="distinct after" value={distinctAfter ?? '—'} />
    </div>
  )
}
