import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import {
  checkHealth,
  checkItemDetail,
  checkManifest,
  checkRecordEvent,
  checkWorkItems,
  describe as describeProblems,
  type Problem,
} from 'ratchet-ui'
import type { BumpDetail, BumpSummary, Manifest, Verdict } from './index'

/**
 * DOES THIS SERVER ACTUALLY SPEAK THE SHARED VOCABULARY, asked of the bytes rather than of the
 * types.
 *
 * `index.ts` re-exports four shapes from `ratchet-ui` and that re-export proves nothing on its own.
 * A type is a promise between two compilers; it is erased before the first byte of JSON is parsed,
 * and every interesting failure lives in the gap it leaves. A field typed `string | null` that
 * arrives absent, a count that arrives as the string `"0"`, a nav item naming a badge the manifest
 * does not define: all three type-check, and all three are what the shared package's validators are
 * for.
 *
 * So this runs those validators over responses this server really sent. The responses are committed
 * next door in `fixtures/`, and `fixtures/capture.sh` is what re-takes them, read only, from a
 * running dashboard.
 *
 * THE FIXTURES ARE CHECKED IN SO THAT THIS RUNS EVERYWHERE, which is the whole point of them. A
 * version of this test that fetched from the dashboard would run on one machine and be skipped on
 * every other, and a skipped test is a green tick that asserts nothing. A version gated on an
 * environment variable is worse: it runs on the day somebody sets it and never again. The cost is
 * that a fixture is a photograph and the server keeps moving, which is what re-taking is for. If a
 * re-take turns this red, that is the news rather than the problem.
 *
 * WHERE IT DISAGREES WITH THE SERVER, IT SAYS SO RATHER THAN LOOKING AWAY. Two of the tests below
 * assert a divergence rather than conformance, spelled out to the field. That is deliberate: an
 * exact expectation fails in both directions, so it goes red when the server is brought into line
 * as well as when it drifts further, and either way somebody reads it.
 */

const fixture = <T>(name: string): T =>
  JSON.parse(readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), 'utf8')) as T

/**
 * Problems with their row index folded away, so an expectation is about the shape of a row and not
 * about how many rows the corpus happened to hold on the day the fixture was taken.
 */
const distinct = (problems: Problem[]): string[] => [
  ...new Set(problems.map((p) => `${p.path.replace(/\[\d+\]/g, '[i]')} ${p.says}`)),
]

describe('the documents a shell mounts this tool by', () => {
  it('serves a manifest with no problems the shared validator can find', () => {
    expect(describeProblems(checkManifest(fixture('manifest')))).toBe('no problems')
  })

  it('serves a health response that names the running version', () => {
    expect(describeProblems(checkHealth(fixture('health')))).toBe('no problems')
  })

  /**
   * The half of the badge contract a validator cannot reach.
   *
   * `checkManifest` catches a nav item naming a badge the manifest does not define, because both
   * halves are in the one document. It cannot catch the next link in the chain: a badge naming a
   * field that the endpoint it points at does not serve. The shell follows it, reads `undefined`,
   * shows no count, and nobody finds out until somebody notices a number that never appears.
   */
  it('serves the badge field the manifest sends the shell looking for', () => {
    const manifest = fixture<Manifest>('manifest')
    const badges = fixture<Record<string, unknown>>('badges')

    for (const item of manifest.nav) {
      if (item.badge === null) continue
      const badge = manifest.badges[item.badge]
      expect(badge, `nav item '${item.label}' names badge '${item.badge}'`).toBeDefined()
      expect(badge?.endpoint).toBe('/api/badges')
      expect(Object.keys(badges)).toContain(badge?.field)
    }
  })
})

describe('a corpus row measured against the shared work item', () => {
  /**
   * THE FINDING, WRITTEN AS AN ASSERTION so that it cannot quietly stop being true.
   *
   * A corpus row is a work item in every respect but its two names. This server calls a row's
   * identity `slug` and its state `verdict`; the shared contract says `id` and `state`. Both are
   * present, both are strings, and the entire cost of adopting the shared shape is renaming them in
   * the Java that serves them.
   *
   * Measured against the whole live response, not just this fixture: at capture time all 1,428 rows
   * reported these two problems and no others.
   */
  it('differs from a work item by exactly two names, and nothing else', () => {
    expect(distinct(checkWorkItems(fixture('bumps')))).toEqual([
      'items[i].id is missing, expected a string',
      'items[i].state is missing, expected a string',
    ])
  })

  it('is a work item once those two names are supplied', () => {
    const rows = fixture<BumpSummary[]>('bumps')
    const renamed = rows.map((row) => ({ ...row, id: row.slug, state: row.verdict }))

    expect(describeProblems(checkWorkItems(renamed))).toBe('no problems')
  })

  /**
   * The runtime twin of the type-level proof in `index.ts`.
   *
   * That one shows the compiler every verdict this file declares is a string the shared wire can
   * carry. It cannot show that the server is only sending verdicts this file declares, because by
   * the time the response is parsed there is no union left to check against. This can.
   */
  it('never carries a verdict this package has not heard of', () => {
    const declared = new Set<Verdict>([
      'PASS',
      'FAIL_build_post',
      'FAIL_test_conservation',
      'FAIL_target_not_bumped',
      'FAIL_no_main_bytecode',
      'NO_BASELINE_NOTESTS',
      'no-baseline',
      'blocked-dependency',
      'behavior-change',
      'infra',
      'bumping',
      'paused',
      'out-of-rounds',
      'queued',
    ])
    const served = fixture<BumpSummary[]>('bumps').map((row) => row.verdict)

    expect(served.filter((verdict) => !declared.has(verdict))).toEqual([])
    expect(served.length).toBeGreaterThan(1)
  })
})

describe('the record a bump writes', () => {
  const asDetail = (name: string) => {
    const detail = fixture<BumpDetail>(name)
    return {
      item: { ...detail.summary, id: detail.summary.slug, state: detail.summary.verdict },
      events: detail.events,
    }
  }

  /**
   * THE SECOND FINDING, AND IT IS ABOUT A NULL RATHER THAN A NAME.
   *
   * The shared contract types a record event's `tool` as optional: present on the kinds that call
   * one, absent on the kinds that do not. This server does something different and, by the shared
   * package's own stated doctrine, better: it emits every field on every line and writes `null`
   * where there is nothing to say. `agent` is declared `string | null` there for exactly that
   * reason and passes here; `tool` was declared optional and does not.
   *
   * So this is a divergence in the contract rather than a defect in the server, and the Java should
   * not change. Widening `tool` to `string | null` in `ratchet-ui` would be the fix, and it is a
   * breaking change for anyone already reading that field, which is why it is recorded here and
   * left for a major version rather than done quietly.
   */
  it('writes a null tool on the lines that called no tool, where the contract expected no key', () => {
    const detail = fixture<BumpDetail>('bump-detail')
    const problems = detail.events.flatMap((event, i) => checkRecordEvent(event, `events[${i}]`))

    expect(distinct(problems)).toEqual(['events[i].tool expected a string, got null'])
  })

  /**
   * The same document with that one field set aside, to show the rest of it conforms. Written as a
   * removal of `tool` rather than as an exception in the expectation, because a test that filters
   * problems by their message would go on passing if a second, unrelated problem appeared with the
   * same wording.
   */
  it('is otherwise a record the shared contract accepts, line for line', () => {
    const detail = asDetail('bump-detail')
    const withoutTool = detail.events.map(({ tool: _tool, ...rest }) => rest)

    expect(describeProblems(checkItemDetail({ ...detail, events: withoutTool }))).toBe('no problems')
  })

  it('is a complete detail with an empty record before a lane has picked the bump up', () => {
    expect(describeProblems(checkItemDetail(asDetail('bump-detail-queued')))).toBe('no problems')
  })
})
