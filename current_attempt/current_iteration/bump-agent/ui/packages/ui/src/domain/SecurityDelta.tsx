import { Tally } from '../primitives/Tally'

export type SecurityDeltaProps = {
  before: number
  after: number
  distinctBefore: number
  distinctAfter: number
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
  const moved = before - after
  return (
    <div style={{ display: 'flex', gap: '26px', flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <Tally label="CRITICAL+HIGH before" value={before} />
      <Tally
        label="after"
        value={after}
        tone={moved > 0 ? 'good' : moved < 0 ? 'alarm' : 'plain'}
      />
      <Tally
        label={moved >= 0 ? 'cleared' : 'introduced'}
        value={Math.abs(moved)}
        tone={moved > 0 ? 'good' : moved < 0 ? 'alarm' : 'plain'}
      />
      <Tally label="distinct before" value={distinctBefore} />
      <Tally label="distinct after" value={distinctAfter} />
    </div>
  )
}
