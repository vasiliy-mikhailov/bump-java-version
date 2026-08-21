import type { Style } from './style'

/**
 * ONE SET OF TABLE CELL STYLES, BECAUSE THERE WERE SIX, AND FOUR OF THEM NOW LIVE IN `ratchet-ui`.
 *
 * Three tables in this dashboard, the corpus, a bump's dependencies, and the two on the security
 * page, each carried their own `th` and `td` constants, copied from whichever table was written
 * first and then adjusted in place. By the time anybody looked they disagreed: the header row was
 * `9px 24px` in all three, the body row was `9px 24px` in one and `8px 24px` in the other two, and
 * the nested table inside a disclosure had a third pair of numbers again. Nothing about the data
 * justified any of it. A reader scrolling from the corpus to a bump's dependencies was reading two
 * tables set one pixel apart, which is exactly the kind of difference that registers as "this is a
 * different page" without ever being noticed as a measurement.
 *
 * NINE PIXELS WON, and the reason is not that four beats two. It is that the header row was 9 in
 * every one of the three tables, and a header set tighter than the body it heads is a rule about
 * nothing: the first row of a table is not more cramped than the rest of it.
 *
 * TWENTY-FOUR PIXELS ACROSS IS NOT A CHOICE AT ALL. It is the page gutter, the same one the header,
 * the tally strip and every section heading use, and `houseStyle.test.ts` asserts it through
 * `STRIP`.
 *
 * THE SIBLING TOOL HAD REACHED THE SAME EIGHT DECLARATIONS FOR ITS COLUMN HEADING, byte for byte,
 * in the same order, down to `.06em` and the strong rule, without either repository having seen the
 * other's. That is why the four shared ones are now one file instead of two, and the only thing the
 * two disagreed about was where the hairline goes: on the row here, on every cell there. The row
 * wins because a rule on the row spans the full width by construction, where a rule on the cells is
 * a run of separate segments that only look continuous while every cell in the row is the same
 * height. See `ratchet-ui`'s `components/table.ts` for the argument in full.
 *
 * THE THREE BELOW DID NOT TRAVEL, and that is deliberate rather than an oversight. Only this
 * dashboard has ever had a table folded inside another table's cell, and the sibling has no
 * monospace constant at all. A shared package that carries a constant one consumer invented and the
 * other cannot use is a shared package that has started collecting things.
 *
 * THE NESTED SCALE IS A DECISION AND IS NOT DRIFT. A table folded inside a disclosure inside another
 * table's cell is a second level of the same fact, and setting it at the page scale would make it
 * compete with its parent instead of reading as detail underneath it. It is named here, beside the
 * re-export of the shared four, so that it stays a decision somebody made rather than a fourth set
 * of numbers somebody typed.
 */
export { CELL, HEAD, ROW, TABLE } from 'ratchet-ui/components'

/** The monospace stack, for the cells that carry coordinates rather than prose. */
export const MONO = 'ui-monospace, Menlo, monospace'

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
