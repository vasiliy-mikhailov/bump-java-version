import type { ReactNode } from 'react'
import { EmptyNote } from './EmptyNote'
import { PAGE_GUTTER } from './Section'

export type LoadedProps<T> = {
  /**
   * THE NOUN, BARE, and the component supplies the article: `record`, `run`, `queue`, `prompts`.
   * It appears in both sentences, which is the point — a screen that says "Reading the queue…"
   * while it waits and "The subject could not be read" when it fails has told the reader about two
   * different things, and the reader has to work out that they are one.
   */
  what: string
  /**
   * WHAT THE FAILURE SENTENCE CALLS IT, when that is not "the {what}".
   *
   * Exactly one screen needs this and it earns it: the bump page is reading a record, so it waits
   * on "Reading the record…", but a reader who arrives at it came for a repository, and "This bump
   * could not be read" names the thing they were looking for rather than the file it is kept in.
   */
  subject?: string
  /** The message from the failed read, or null while nothing has failed. */
  failed: string | null
  /** What was read, or null until it arrives. */
  value: T | null
  /**
   * The page header, for a screen that is a whole page rather than a card inside one.
   *
   * A PAGE KEEPS ITS HEADER UP THROUGH BOTH WAITS. A reader who follows a link and gets a blank
   * document cannot tell a slow read from a broken one, and has nothing to go back with. Passing it
   * also insets the note to the page gutter, because a note under a full-bleed header is the only
   * thing on the page holding the left edge; a card inside an already-inset panel passes nothing
   * and gets the note alone.
   */
  header?: ReactNode
  children: (value: T) => ReactNode
}

/**
 * THE THREE STATES OF A THING BEING READ, in the one order that is correct.
 *
 * This was written out nine times across five files, as a pair of early returns in four of them, a
 * pair of full-page branches in three, and a nested ternary in the fifth. The wording drifted, the
 * shape of the wrapper drifted, and one of the copies had the two states the wrong way round for a
 * while: a screen that checks emptiness before failure reports a failed read as "still loading",
 * and a reader waits for something that is never coming.
 *
 * FAILURE BEATS EMPTINESS. A read that failed also has no value, so testing the value first
 * describes every failure as a wait. The order here is the whole logic of the component and it is
 * the reason this is a component rather than a snippet.
 *
 * EMPTINESS IS STATED RATHER THAN DRAWN AS NOTHING, which is `EmptyNote`'s argument and this
 * inherits it: a page that renders nothing while it waits is indistinguishable from a page that
 * finished and found nothing.
 */
export function Loaded<T>({ what, subject, failed, value, header, children }: LoadedProps<T>) {
  if (failed === null && value !== null) {
    return <>{children(value)}</>
  }
  const note = (
    <EmptyNote>
      {failed === null
        ? `Reading the ${what}…`
        : `${subject ?? `The ${what}`} could not be read: ${failed}`}
    </EmptyNote>
  )
  if (header === undefined) {
    return note
  }
  return (
    <>
      {header}
      <div style={{ padding: PAGE_GUTTER }}>{note}</div>
    </>
  )
}
