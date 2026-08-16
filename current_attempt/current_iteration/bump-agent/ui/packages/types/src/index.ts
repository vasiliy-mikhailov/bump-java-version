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
  /** In the manifest, not yet picked up by a lane. Most of a fresh sweep is this. */
  | 'queued'

/** One row of the corpus: a repository, at a commit, moved between two LTS levels. */
export type BumpSummary = {
  slug: string
  repo: string
  sha: string
  from: number
  to: number
  verdict: Verdict
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
  /** Which agent spoke, for `asked` and `tool`. */
  agent?: string
  /** Which stage recorded it, for `applied`. */
  stage?: string
  tool?: string
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

/** Everything one bump page needs, in one response. */
export type BumpDetail = {
  summary: BumpSummary
  chain: ChainStage[]
  events: TraceEvent[]
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

/** The manifest a shell reads. Mirrors spec 17 of the sibling tool, field for field. */
export type Manifest = {
  id: string
  name: string
  description: string
  version: string
  basePath: string
  assetPrefix: string
  api: string
  health: string
  nav: { label: string; path: string; badge: string | null }[]
  badges: Record<string, { endpoint: string; field: string }>
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

export type Health = { ok: true; version: string } | { ok: false; why: string }
