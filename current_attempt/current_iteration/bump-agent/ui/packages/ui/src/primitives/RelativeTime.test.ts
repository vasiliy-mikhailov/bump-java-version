import { describe, expect, it } from 'vitest'
import { duration, relative, spellMinutes } from './RelativeTime'

/**
 * A DURATION IS NOT A TIME AGO, AND THE PAGE NEEDS BOTH.
 *
 * `relative` answers "when did this last speak"; `duration` answers "how long did this cost". They
 * were one function that rounded to whole minutes, which is why a bump that took 8m 45s and one
 * that took 9m 29s both read "9m" in a column whose entire job is telling them apart.
 */
describe('duration', () => {
  it('keeps seconds below a minute, where they are all there is', () => {
    expect(duration(0)).toBe('0s')
    expect(duration(45_000)).toBe('45s')
    expect(duration(59_400)).toBe('59s')
  })

  it('keeps seconds beside minutes, which is the range a bump lives in', () => {
    // The sibling's own column reads "8m 45s". Rounding this away loses the comparison the
    // column exists to support.
    expect(duration(8 * 60_000 + 45_000)).toBe('8m 45s')
    expect(duration(9 * 60_000)).toBe('9m')
  })

  it('drops seconds once there are hours, where they are noise', () => {
    expect(duration(60 * 60_000)).toBe('1h')
    expect(duration(8 * 60 * 60_000 + 33 * 60_000)).toBe('8h 33m')
    expect(duration(87 * 60 * 60_000 + 51 * 60_000 + 30_000)).toBe('87h 51m')
  })

  it('floors rather than rounds, so nothing reads as longer than it was', () => {
    // 119s is one minute and 59 seconds. Rounding the minutes gave "2m" for something that had
    // not reached two minutes.
    expect(duration(119_000)).toBe('1m 59s')
  })

  it('never goes negative, because clocks disagree', () => {
    // now is taken once per render and a trace timestamp comes from another machine's clock. A
    // few milliseconds the wrong way should read as zero, not as "-1s" or a 56-year span.
    expect(duration(-5_000)).toBe('0s')
  })
})

describe('spellMinutes', () => {
  it("speaks the estimator's unit back", () => {
    expect(spellMinutes(25)).toBe('25m')
    expect(spellMinutes(60)).toBe('1h')
    expect(spellMinutes(5271)).toBe('87h 51m')
  })

  it('says nothing clever about zero', () => {
    expect(spellMinutes(0)).toBe('0s')
  })
})

describe('relative', () => {
  it('still says ago, because it answers a different question', () => {
    expect(relative(1_000_000, 1_000_000 + 14_000)).toBe('14s ago')
    expect(relative(0, 3 * 60 * 60_000)).toBe('3h ago')
  })
})
