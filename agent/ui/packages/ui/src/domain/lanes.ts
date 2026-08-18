import type { AgentPrompt } from '@bjv/types'

/**
 * ONE LANE PER DEPENDENCY-MANAGEMENT PLATFORM, SO THE THREE CAN BE READ AGAINST EACH OTHER.
 *
 * Fourteen agents inside the module walk exist once per platform and are named
 * `before-pins-planner@spring-boot` and so on. The interesting thing about them is the difference
 * between the three, and a reader can only see a difference by putting the three side by side.
 *
 * THE COLUMNS USED TO BE AN ACCIDENT. The cards sat in a `repeat(auto-fit, minmax(420px, 1fr))`
 * grid and flowed in server order, so on a wide screen three platforms landed in three columns and
 * looked deliberate. They were not: the column count came from the viewport, so at a narrower width
 * the same list wrapped to two and read `planner@spring-boot | planner@quarkus` above
 * `planner@adhoc | doer@spring-boot`, comparing a planner to a doer with nothing to say it had
 * started doing that.
 *
 * So the placement is a lookup rather than a flow. Every cell is asked for by (stem, platform), and
 * a platform with no agent for a stem leaves a hole where the eye expects one instead of pulling
 * the next card into its place. A hole is a bug you can see; a shift is a bug you read.
 */
export type LaneCell = {
  platform: string
  /** Null when this stem has no agent for this platform, which is a gap and not a shift. */
  prompt: AgentPrompt | null
}

export type LaneRow = {
  /** The agent name without its platform, which is the same role in every lane. */
  stem: string
  cells: LaneCell[]
}

export type Lanes = {
  /** Empty when nothing here is platform-keyed, which is how a caller knows to draw a plain grid. */
  platforms: string[]
  rows: LaneRow[]
}

/** The part before the `@`, which is the same agent in every lane. */
export function stemOf(name: string): string {
  const at = name.indexOf('@')
  return at < 0 ? name : name.slice(0, at)
}

/** The part after the `@`, empty for an agent that exists once. */
export function platformOf(name: string): string {
  const at = name.indexOf('@')
  return at < 0 ? '' : name.slice(at + 1)
}

/**
 * Group agents into lanes, or report that there are none.
 *
 * <p>MIXED IS NOT LANED. A block whose agents are partly keyed and partly not has no honest column
 * layout: the unkeyed one belongs to every lane and to none, and putting it in the first would say
 * it was that platform's. The caller falls back to the flat grid, which is right for the one block
 * this happens in, the platform stage itself, whose three agents decide the platform and therefore
 * cannot be keyed by it.
 */
export function lanesOf(agents: AgentPrompt[]): Lanes {
  const keyed = agents.filter((a) => platformOf(a.name) !== '')
  if (keyed.length === 0 || keyed.length !== agents.length) {
    return { platforms: [], rows: [] }
  }

  // FIRST APPEARANCE, NOT SORTED. The server sends them in the order the factory defines them, and
  // that order is the one every stage shares; sorting here would put adhoc first on this page and
  // nowhere else, so a reader moving between stages would find the lanes had swapped under them.
  const platforms: string[] = []
  const stems: string[] = []
  for (const a of agents) {
    const p = platformOf(a.name)
    if (!platforms.includes(p)) {
      platforms.push(p)
    }
    const s = stemOf(a.name)
    if (!stems.includes(s)) {
      stems.push(s)
    }
  }

  const rows = stems.map((stem) => ({
    stem,
    cells: platforms.map((platform) => ({
      platform,
      prompt: agents.find((a) => stemOf(a.name) === stem && platformOf(a.name) === platform) ?? null,
    })),
  }))

  return { platforms, rows }
}
