import { describe, expect, it } from 'vitest'
import { relative } from './RelativeTime'

/**
 * A DURATION IS NOT A TIME AGO, AND THE PAGE NEEDS BOTH.
 *
 * `relative` answers "when did this last speak"; `duration` answers "how long did this cost". They
 * were one function that rounded to whole minutes, which is why a bump that took 8m 45s and one
 * that took 9m 29s both read "9m" in a column whose entire job is telling them apart.
 *
 * ONLY ONE OF THE TWO IS TESTED HERE ANY MORE. `duration` and `spellMinutes` are `ratchet-ui`'s,
 * because both dashboards had written them and the two versions differed only in the rounding, and
 * their cases live in that package's `time.test.ts` along with the four inputs where the two
 * disagreed. `relative` did not travel: the sibling's crosses into minutes at ninety seconds, floors
 * rather than rounds, has a day rung this one reaches at forty-eight hours, and is a component with
 * a timer in it. Those are two products.
 */
describe('relative', () => {
  it('still says ago, because it answers a different question', () => {
    expect(relative(1_000_000, 1_000_000 + 14_000)).toBe('14s ago')
    expect(relative(0, 3 * 60 * 60_000)).toBe('3h ago')
  })

  it('rounds into the next rung rather than reporting a clock that has already turned over', () => {
    // 59.6 seconds is not "59s ago" on a page whose other readings have moved on. The same argument
    // decided `duration`'s rounding when the two dashboards' versions were settled against
    // each other.
    expect(relative(0, 59_600)).toBe('1m ago')
  })

  it('reads a clock that went backwards as no time at all', () => {
    // A trace timestamp comes from another machine's clock. A few milliseconds the wrong way should
    // read as zero rather than as "-1s ago" or a fifty-six-year span.
    expect(relative(1_000, 0)).toBe('0s ago')
  })
})
