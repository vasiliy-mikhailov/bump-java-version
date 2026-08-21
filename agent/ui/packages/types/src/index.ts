/**
 * THE WIRE, WRITTEN DOWN ONCE.
 *
 * Every shape here is produced by the Java side and consumed by the pages, and the two are deployed
 * as one image, so a mismatch is a build error rather than a runtime surprise — but only if the
 * shapes live somewhere both halves can be checked against. This is that place.
 *
 * ADDITIVE CHANGES ONLY, for the same reason the mount contract says so: a shell, and a reader's
 * open tab, will be older than the server as often as not. Adding a field is safe. Renaming one
 * breaks a page nobody rebuilt.
 *
 * Nothing here is a colour, a label or a width. A `verdict` is `PASS`, not green: the moment a
 * server sends a tone over the wire, it has made a design decision that cannot be tested for, and
 * the page can no longer be the only place that knows what a state looks like.
 */

import type { State } from 'ratchet-ui/wire'

/**
 * THE PART OF THE WIRE THAT IS NOT OURS, taken from `ratchet-ui` rather than written down twice.
 *
 * A sibling tool serves a dashboard of this shape over a different subject, and the two of them
 * were describing the same documents in two places. These four are where that costs nobody a
 * rename: `Manifest`, `NavItem`, `Badge` and `Health` were already identical field for field,
 * because both tools were written against the same mounting spec. Adopting them is an import and a
 * deleted declaration.
 *
 * And it is proved rather than asserted. `conformance.test.ts` runs the shared package's validators
 * over responses captured from this very server, which is a different question from whether two
 * type declarations look alike: a type is a promise between compilers and says nothing about the
 * bytes a running server sends.
 *
 * WHAT IS DELIBERATELY NOT TAKEN is most of the file below, and one omission is worth naming.
 * `BumpSummary` is not the shared `WorkItem`, and the difference is real rather than an oversight:
 * this server calls a row's identity `slug` and its state `verdict`, where the shared contract says
 * `id` and `state`. Two renames, in the Java, which is a change to a running sweep rather than a
 * change to a type. The conformance test measures that gap exactly, and finds it is the whole of
 * the difference across every row, so whoever decides to close it can read the size of the job
 * instead of estimating it.
 *
 * `Finding` IS A FALSE FRIEND and that is why it is not among these. Both packages export the name.
 * Here it is something the supervisor noticed across bumps; there it is a claim about work carrying
 * a critic's judgement, and that package's `Verdict` is the judgement rather than this file's
 * settlement state. Two pairs of names, each pair one careless import from being swapped with no
 * compiler complaint. The shared package kept them apart on purpose and so does this one.
 */
export type { Badge, Health, Manifest, NavItem } from 'ratchet-ui/wire'

/** What the gate or the closers settled a bump as. The corpus vocabulary, unabridged. */
export type Verdict =
  | 'PASS'
  | 'FAIL_build_post'
  | 'FAIL_test_conservation'
  | 'FAIL_target_not_bumped'
  | 'FAIL_no_main_bytecode'
  | 'NO_BASELINE_NOTESTS'
  | 'no-baseline'
  | 'blocked-dependency'
  | 'behavior-change'
  | 'infra'
  | 'bumping'
  /**
   * A LANE RAN OUT OF ITS WALL-CLOCK BUDGET AND THE BUMP IS WAITING FOR THE NEXT ONE.
   *
   * Not a verdict, and deliberately not folded into `queued`: a bump that has never started and a
   * bump that has burned three lane budgets are both waiting, and a reader who cannot tell them
   * apart cannot find the second one. Its checkout and its journal are kept, so the next lane
   * continues from where this one stopped.
   */
  | 'paused'
  /**
   * The harness stopped spending lanes on a bump that never reached a verdict.
   *
   * TERMINAL, AND NOT A JUDGEMENT ABOUT THE PROJECT, which is the thing to get right about it. It
   * says a repository held N rounds of the budget without settling; it says nothing about whether
   * the bump was possible. Anything that buckets states into successes and failures has to be told
   * about this one on purpose.
   */
  | 'out-of-rounds'
  /** In the manifest, not yet picked up by a lane. Most of a fresh sweep is this. */
  | 'queued'

/**
 * THE COMPILER'S PROOF THAT THIS VOCABULARY FITS THE SHARED WIRE, costing nothing at runtime.
 *
 * `ratchet-ui`'s `State` is a bare `string`, which reads like a type that gave up and is not. The
 * two pipelines sharing these shapes have disjoint state vocabularies, and the authority on either
 * of them is a `grep` in a shell script rather than a union in TypeScript. This project's is at
 * `agent/run.sh:87`, where a bump counts as still working because its settlement matches
 * `"state":"(bumping|requeued)"`. A union in a shared package would make that two places deciding,
 * free to drift, and the direction they drift in is the bad one: a bump finished on the page and
 * unfinished to the loop that is supposed to stop working on it.
 *
 * So the shared type stays a string, this file keeps the real vocabulary, and the assignability is
 * asserted here instead. Written as a constrained type parameter rather than as
 * `'PASS' satisfies Verdict`, because that form proves one member and this proves all twelve,
 * including the one somebody adds next year.
 */
type Carries<Wide, Narrow extends Wide> = Narrow
type _TheWireCarriesEveryVerdict = Carries<State, Verdict>

/** One row of the corpus: a repository, at a commit, moved between two LTS levels. */
export type BumpSummary = {
  slug: string
  repo: string
  sha: string
  from: number
  to: number
  verdict: Verdict
  /**
   * WHICH ROUND OF THE LANE BUDGET THIS ROW BELONGS TO, from one upwards.
   *
   * Null means nobody was counting, which is not round one: most of this corpus settled before
   * lanes had a budget at all. It also resets when the pipeline changes under a paused bump,
   * because that run starts over, so a repository that has been picked up five times and never
   * once resumed reads as round one here. The record page shows the whole boundary history for
   * exactly that reason.
   */
  round: string | null
  /**
   * NULL, NOT ABSENT, and the distinction is load-bearing.
   *
   * The Java emits every field on every row and uses JSON `null` for "no value yet". Typing these
   * `?: string` says the KEY may be missing, which is a different thing, and a component written
   * against it checks `=== undefined` and renders the null as an empty cell with the separator
   * still around it. That shipped: a running bump drew a bare arrow in the CVE column.
   */
  because: string | null
  baselineGreen: boolean
  gateGreen: boolean
  /** Tests passing before the bump, and how many of them were lost. Null until it settles. */
  preTests: number | null
  lost?: number
  /** CRITICAL+HIGH, before and after. Null when the scan could not be taken. */
  cvesBefore: number | null
  cvesAfter: number | null
  /**
   * When this bump first spoke, or 0 while it is only queued.
   *
   * <p>With `at`, this answers the question a single timestamp cannot: a bump can be four hours old
   * and still working, or four hours old and stopped, and those need different reactions.
   */
  startedAt: number
  /**
   * Milliseconds since the epoch of the last event on this bump.
   *
   * <p>THE LAST EVENT, not the last settlement. It used to be the settlement's timestamp, and a
   * settlement is written when a bump starts and when it ends — so a bump grinding away for an hour
   * showed the moment it began, and the column could not answer the one thing it exists for.
   */
  at: number
  /** Trace lines this bump has written. Zero while it is only queued. */
  events: number
  /**
   * WHAT THE SAME WORK WOULD HAVE COST A PERSON, in minutes, as the estimator triad priced it.
   *
   * Null until the bump settles, because the estimate is taken from what LANDED and a bump still
   * working has not finished landing it. An estimate, and kept in its own column for that reason:
   * it must never be added to a measured number.
   */
  humanMinutes: number | null
  /**
   * HOW MUCH OF WHAT THE TARGET NEEDS THIS PROJECT REACHED, which the verdict does not say.
   *
   * Null means nothing was measured, and that is not nought: a bump settled before anyone was
   * measuring, and a project declaring none of these floors, are both absent rather than failing.
   */
  bomMet: number | null
  bomMissed: number | null
  /** The same two, measured against the tree as it stood before the bump touched it. */
  bomMetBefore: number | null
  bomMissedBefore: number | null
  /**
   * THE COMPARABLE HALF, and the only honest basis for a before-and-after rate.
   *
   * The after-scan only runs on a green gate, so a bump that never reached one is measured against
   * a resolved tree before and none after: its "after" is smaller because less was measured, not
   * because anything was fixed. These count only floors that applied on both sides.
   */
  bomPairApplied: number | null
  bomPairMissedBefore: number | null
  bomPairMissedAfter: number | null
  /** The floors it still sits below, named, so the number can be argued with. */
  bomOutstanding: string | null
  /**
   * WHICH PIPELINE PRODUCED THIS ROW. Null on every bump that settled before the stamp
   * existed, which is most of the corpus and always will be, so an unstamped row is the
   * ordinary case rather than a failure and must not be drawn as one.
   *
   * `commit` is the git commit the image was built from, with a `-dirty` suffix when the tree
   * it was built from was not clean. `image` is the image id the LANE started from, which is
   * not necessarily the one the tag points at now: a running lane keeps its image however many
   * times a deploy moves the tag under it, and this sweep was deployed seven times in a day.
   */
  commit: string | null
  image: string | null
  /**
   * WHY TWO HASHES WHEN A COMMIT IS ALREADY HERE, which is the whole reason these exist.
   *
   * Prompt and bill-of-materials edits live in a store beside the results, OUTSIDE the image,
   * and take effect on the next lane to start. So a commit or an image alone calls two runs
   * the same pipeline exactly when one of them has been edited from the settings page, which
   * is precisely the case a reader is trying to tell apart.
   *
   * `prompts` is eight hex characters over every system prompt for that hop, overrides
   * included; `boms` the same over both bill-of-materials lists for that hop. Equal means the
   * two runs could not have been handed different instructions. Different means they were, and
   * comparing their outcomes is comparing two pipelines rather than two repositories.
   */
  prompts: string | null
  boms: string | null
}

/**
 * A step of the chain, as the page draws it.
 *
 * `role` is the whole point of the redesign: a reader could not tell which of two agents in a stage
 * held the loop, because both were called "critic" or "-er". Plan, do, verify is the vocabulary.
 */
export type StepRole = 'planner' | 'doer' | 'verifier'

export type ChainStep = {
  name: string
  role: StepRole
  /** False for a deterministic step: the baseline, the gate, a sub-chain. */
  agent: boolean
  /** How many times this step spoke. Zero means the bump never reached it. */
  spoke: number
}

export type ChainStage = {
  title: string
  /** The stage this one runs inside, empty for a top-level stage. */
  within: string
  steps: ChainStep[]
}

/** One thing that happened, in the order it happened. */
export type TraceEvent = {
  at: number
  /**
   * `exchange` is the wire itself, recorded by a listener under the harness rather than by the
   * harness choosing to report: one per model call, including the ones that errored before any
   * answer existed. The others are the curated account.
   */
  kind:
    | 'progress'
    | 'applied'
    | 'asked'
    | 'tool'
    | 'thought'
    | 'built'
    | 'settled'
    | 'priced'
    | 'exchange'
  /**
   * WHICH AGENT SPOKE, OR NULL ON A LINE NO AGENT PRODUCED. Null, and never absent.
   *
   * These three were declared `?: string`, and the server has never once sent them that way.
   * `Json.optional` writes the key on every line and gives it JSON null when there is nothing to
   * say, so `agent === undefined` is false on every deterministic step there has ever been. The
   * feed asked exactly that question and drew three empty labels on every line of every record.
   *
   * Which is the same rule as `because` on the row above, and the second time this distinction has
   * cost something here. It was found by running the shared package's validators over responses
   * this server actually sent: `conformance.test.ts`, and `EventFeed.test.tsx` for the rendering.
   */
  agent: string | null
  /** Which stage recorded it, or null on a line no stage claimed. */
  stage: string | null
  /** Which tool was called, or null on the kinds that call none. */
  tool: string | null
  /** The body. Long, frequently: the page folds it rather than the server truncating it. */
  text: string
  /** The server's own token counts, present on `exchange` and zero elsewhere. */
  inTokens: number
  outTokens: number
  /** Wall time of the call in milliseconds. */
  ms: number
}

/** A dependency the scan resolved, and what the bump did to it. */
export type Package = {
  name: string
  /** Which module resolved it. The same package can appear once per module. */
  module: string
  versionBefore: string | null
  versionAfter: string | null
  cvesBefore: number
  /**
   * NULL WHEN THE AFTER SCAN DID NOT SEE THIS PACKAGE, which is not the same as zero.
   *
   * The after scan runs only on a green gate, so most bumps never take one. Sending zero made
   * every vulnerable dependency on those bumps render green as cleared.
   */
  cvesAfter: number | null
}

/**
 * ONE ROUND OF THE LANE BUDGET THAT ENDED, AND WHICH PIPELINE ENDED IT.
 *
 * The fingerprint travels because it is the diagnosis rather than decoration: when it differs from
 * the boundary before it, the harness was deployed while this bump was waiting and the next lane
 * started the work over instead of continuing it. A round number alone cannot say that, because a
 * fresh start resets it.
 */
export type RoundBoundary = {
  at: number
  /** `paused` for a boundary, `out-of-rounds` when the harness stopped spending on it. */
  state: string
  round: string | null
  because: string | null
  commit: string | null
  image: string | null
  prompts: string | null
  boms: string | null
}

/** Everything one bump page needs, in one response. */
export type BumpDetail = {
  summary: BumpSummary
  chain: ChainStage[]
  events: TraceEvent[]
  /** Every round of the lane budget this bump has ended, oldest first. Empty for almost all of them. */
  rounds: RoundBoundary[]
  packages: Package[]
  /** Distinct CRITICAL+HIGH, which is not the sum of the rows above: see the module column. */
  /** `after` is null when no after scan was taken, which is most bumps. */
  cves: {
    before: number
    after: number | null
    distinctBefore: number
    distinctAfter: number | null
  }
}

/** What the supervisor noticed across bumps that no single bump could see. */
export type Finding = {
  at: number
  bump: string
  kind: string
  what: string
  /** Whether the bump it concerns is still held back. */
  held: boolean
}

/** One agent's prompt, for the settings page. */
export type AgentPrompt = {
  name: string
  role: StepRole
  stage: string
  /**
   * The stage this one runs inside, empty at the top level.
   *
   * THE CHAIN IS A PROGRAM WITH BLOCKS. modules and troubleshoot each run a sub-chain between their
   * planner and their verifier, and a flat list reads as fourteen stages in a row rather than two
   * loops with bodies.
   */
  within: string
  /** The deterministic step that IS this stage's loop, empty when its doing is an agent. */
  loop: string
  /**
   * How often this stage runs, and whether it runs at all. Empty means once, unconditionally.
   *
   * NESTING SAYS WHERE, NOT HOW MANY TIMES. Under modules only bump walks the module list; both pin
   * phases run once for the repository with the module list handed to them as text. And troubleshoot
   * is the repair arm of a turn loop with the gate rather than a stage that follows the module work.
   */
  repeats: string
  /**
   * Which bill of materials this stage works from: `enables`, `hardens`, or empty.
   *
   * The two halves are different kinds of claim rather than two timings of one. What enables the
   * bump is a precondition, and below one of those the bump does not happen at all. What hardens
   * the result is polish on a project that already builds and tests green.
   */
  reads: string
  /** How many rows that list has for this hop, counted from the file. Zero when it reads none. */
  pins: number
  description: string
  /** What is in force: the edit if there is one, otherwise the code's own. */
  prompt: string
  /**
   * The code's own, always. The page cannot offer a revert without something to revert TO, and a
   * reader comparing an edit against the built-in should not have to redeploy to see it.
   */
  builtIn: string
  edited: boolean
}

/** The header's two numbers: how big the corpus is, and whether it is still moving. */
export type Summary = {
  bumps: number
  /** Trace lines across every bump that has started. */
  events: number
  /** Epoch ms of the newest trace write, or 0 when nothing has run. */
  lastEventAt: number
}

/**
 * WHERE THE CORPUS'S CLEARED VULNERABILITIES WENT, aggregated over the bumps that were scanned
 * both before and after.
 *
 * Everything named `before`/`after` here is DISTINCT: one count per package and version, however
 * many modules resolved it. `occurrencesBefore`/`occurrencesAfter` are the same bumps counted the
 * way the list page counts them, carried so a reader can reconcile the two headline numbers
 * instead of concluding one of them is broken.
 */
export type Security = {
  before: number
  after: number
  removed: number
  measured: number
  /** Percent of `before` cleared, null when nothing was vulnerable to begin with. */
  rate: number | null
  occurrencesBefore: number
  occurrencesAfter: number
  byPackage: {
    name: string
    before: number
    after: number
    bumps: number
    /**
     * The versions this package ENDED UP AT, best outcome first.
     *
     * The destination, not the pair: the source is what a reader is trying to get away from and
     * knowing it turned tomcat into thirteen rows to make one point. `to` is null when the package
     * was not in the after scan at all, which is the upgrade dropping or replacing it.
     */
    versions: { to: string | null; before: number; after: number; bumps: number }[]
  }[]
  byBump: { slug: string; repo: string; from: number; to: number; before: number; after: number }[]
}
