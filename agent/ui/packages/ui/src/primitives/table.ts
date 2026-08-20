import type { Style } from './style'

/**
 * ONE SET OF TABLE CELL STYLES, BECAUSE THERE WERE SIX.
 *
 * Three tables in this dashboard — the corpus, a bump's dependencies, and the two on the security
 * page — each carried their own `th` and `td` constants, copied from whichever table was written
 * first and then adjusted in place. By the time anybody looked they disagreed: the header row was
 * `9px 24px` in all three, the body row was `9px 24px` in one and `8px 24px` in the other two, and
 * the nested table inside a disclosure had a third pair of numbers again. Nothing about the data
 * justified any of it. A reader scrolling from the corpus to a bump's dependencies was reading two
 * tables set one pixel apart, which is exactly the kind of difference that registers as "this is a
 * different page" without ever being noticed as a measurement.
 *
 * NINE PIXELS WINS, and the reason is not that four beats two. It is that the header row was 9 in
 * every one of the three tables, and a header set tighter than the body it heads is a rule about
 * nothing: the first row of a table is not more cramped than the rest of it. Making the body match
 * the header is the only choice that leaves every table internally consistent, and it happens to be
 * what the largest table on the site — the corpus, which is what most readers spend their time in —
 * already did.
 *
 * TWENTY-FOUR PIXELS ACROSS IS NOT A CHOICE AT ALL. It is the page gutter, the same one the header,
 * the tally strip and every section heading use, and `houseStyle.test.ts` asserts it through
 * `STRIP`. A table cell that inset its content differently would make its column look like it
 * belonged to a different page than the heading above it.
 *
 * THE NESTED SCALE IS DELIBERATE AND IS NOT DRIFT. A table folded inside a disclosure inside
 * another table's cell is a second level of the same fact, and setting it at the page scale would
 * make it compete with its parent instead of reading as detail underneath it. It is named here, in
 * the same file, so that it is a decision somebody made rather than a fourth set of numbers
 * somebody typed.
 */

/** The monospace stack, for the cells that carry coordinates rather than prose. */
export const MONO = 'ui-monospace, Menlo, monospace'

/** Full width, collapsed, at the body size. Every table on the site. */
export const TABLE: Style = { width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' }

/** The hairline between two rows. On the row, not the cell, so it spans the whole width. */
export const ROW: Style = { borderTop: '1px solid var(--border-soft)' }

/** A column heading: small, uppercase, letterspaced, tertiary, on a strong rule. */
export const HEAD: Style = {
  textAlign: 'left',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  padding: '9px 24px',
  borderBottom: '1px solid var(--border-strong)',
}

/**
 * A body cell. Top-aligned because several columns carry two lines — a duration over an event
 * count — and a row whose cells centre themselves independently has no baseline at all.
 */
export const CELL: Style = { padding: '9px 24px', verticalAlign: 'top' }

/** The same heading, one level in: smaller, on the soft rule, at the nested inset. */
export const HEAD_NESTED: Style = {
  textAlign: 'left',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  fontSize: '10.5px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  padding: '5px 10px',
  borderBottom: '1px solid var(--border-soft)',
}

/** The nested body cell. Monospace, because everything at this level is a version number. */
export const CELL_NESTED: Style = { padding: '5px 10px', verticalAlign: 'top', fontFamily: MONO }
