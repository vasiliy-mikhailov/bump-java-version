import { pipelineOf, type PipelineStamp } from './pipeline'

export type PipelineMarkProps = { stamp: PipelineStamp }

/**
 * ONE CELL THAT SAYS WHICH PIPELINE PRODUCED THE ROW.
 *
 * A sweep runs for a fortnight and the harness changes daily, so a reader comparing two bumps
 * cannot otherwise tell a difference in the repository from a difference in the program that bumped
 * it. Four fields answer that, and four fields in a table that already carries nine columns answer
 * nothing, so they are one token: the commit, then the fold of everything the commit does not
 * cover. Same token, same pipeline. The four fields as recorded are on the hover, which is where
 * the second question, "which pipeline was it", is asked.
 *
 * UNSTAMPED IS THE COMMON CASE AND IS WRITTEN OUT AS A WORD. Every bump before today has no stamp
 * and never will, so most of this column will say so for a while. A dash would file it with
 * "nothing was measured here", which is a different claim about a different kind of gap, and an
 * empty cell would read as a page that failed to render.
 */
export function PipelineMark({ stamp }: PipelineMarkProps) {
  const pipeline = pipelineOf(stamp)
  if (pipeline === null) {
    return (
      <span
        style={UNSTAMPED}
        title="this bump settled before the pipeline stamp existed, so what produced it was never recorded. Nothing can fill it in now."
      >
        unstamped
      </span>
    )
  }
  return (
    <span style={MARK} title={pipeline.detail}>
      {pipeline.head}
      {pipeline.fold === '' ? null : (
        <>
          {/* The separator is dimmed and the two halves are not, because the halves are what a
              reader compares and the punctuation between them is not part of the answer. */}
          <span style={{ color: 'var(--text-tertiary)' }}>·</span>
          {pipeline.fold}
        </>
      )}
    </span>
  )
}

const MARK = { color: 'var(--text-secondary)', fontSize: '11.5px', whiteSpace: 'nowrap' } as const
const UNSTAMPED = { color: 'var(--text-tertiary)', fontSize: '11px' } as const
