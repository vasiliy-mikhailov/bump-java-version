import type { Style } from '../primitives/style'
import { CORNER_BUSY, CORNER_BUTTON, CORNER_MARK, CORNER_REFUSED, HEADER_NOTE } from './PageHeader'

/**
 * SET A BUMP ASIDE, AND BRING IT BACK. The converse of the rerun control, and its sibling.
 *
 * THE SEMANTICS ARE NOT GUESSABLE FROM THE VERB, which is the whole reason this component owns its
 * copy instead of leaving a page to caption it. Three things are true at once, and a reader who has
 * only one of them will make an expensive mistake:
 *
 * <ul>
 *   <li>Setting a bump aside FREES ITS LANE for work that can progress. There are sixteen lanes and
 *       a lane runs for hours, so a repository going nowhere is not costing itself, it is costing
 *       the fifteen bumps queued behind it.</li>
 *   <li>It is NOT A DROP. The bump keeps its place in the corpus and its verdict, and run.sh clears
 *       what was set aside once it is all that remains, so it runs at the END rather than never.</li>
 *   <li>It is REVERSIBLE, from this same button, which is why one control toggles rather than two
 *       sitting beside each other daring the reader to pick.</li>
 * </ul>
 *
 * THE STATE IS ON THE CONTROL, not only in a note beside it. A toggle that looks the same in both
 * of its states is a coin flip, so the mark mirrors, `aria-pressed` carries the same fact to a
 * screen reader, and a bump that is held wears the aside tone rather than the tertiary grey every
 * other corner control wears.
 */
export type SetAsideButtonProps = {
  /** Is this bump held back from the sweep right now, as far as the page knows? */
  setAside: boolean
  /** An ask is in flight. */
  busy?: boolean
  /** The last ask was refused, so the control wears it rather than only the note beside it. */
  refused?: boolean
  /** Ask for the opposite of `setAside`. The caller owns the endpoint. */
  onAsk: () => void
}

/**
 * What the control claims, in full, on hover and to a screen reader.
 *
 * A TOOLTIP LONG ENOUGH TO BE A SENTENCE, deliberately. The cost of the wrong mental model here is
 * a reader who never touches the button because they think it abandons a repository, or one who
 * uses it as a delete. Both cost the sweep more than a long title costs a reader.
 */
export const ASIDE_WHY =
  'Set this bump aside, so its lane can take work that can progress. It is not dropped: it keeps ' +
  'its place in the corpus and runs at the end, once the bumps that were set aside are all that ' +
  'is left. This same button brings it back.'

export const BACK_WHY =
  'Bring this bump back. It rejoins the queue now rather than waiting until nothing else is left, ' +
  'and it takes a lane again as soon as one is free. This same button sets it aside.'

export type SetAsideNoteProps = {
  /**
   * The reason recorded on the marker, when there is one to read.
   *
   * MOSTLY NOT THIS READER'S WORDS. The supervisor has been writing these markers since before
   * there was a button, off a digest of the whole sweep, and its reason is the answer to the
   * question a reader finding a held bump actually asks. Absent is ordinary rather than wrong: the
   * listing that tells this page a bump is held does not carry reasons.
   */
  why?: string
}

/**
 * The prose that goes beside the control while a bump is held.
 *
 * SHOWN ONLY IN THE STATE IT EXPLAINS. A permanent caption under a button is read once and then
 * never again; this appears at the moment it answers a question the reader actually has, which is
 * "what did I just do to this repository, and can I undo it".
 *
 * The bold word carries the state and the rest is prose, for the same reason the refusal note is
 * shaped this way: the fact must not be carried by colour alone.
 */
export function SetAsideNote({ why }: SetAsideNoteProps) {
  return (
    <div role="status" style={HEADER_NOTE}>
      <b style={{ color: 'var(--state-blocked-dependency)' }}>Set aside.</b> Its lane is free for
      work that can progress. It is still in the corpus: it runs at the end, once the bumps that
      were set aside are all that is left. The button above brings it back.
      {why === undefined || why === '' ? null : <div style={REASON}>{why}</div>}
    </div>
  )
}

export function SetAsideButton({ setAside, busy, refused, onAsk }: SetAsideButtonProps) {
  const why = setAside ? BACK_WHY : ASIDE_WHY
  return (
    <button
      type="button"
      // aria-pressed, not a label that only reads well once. A toggle whose accessible name changes
      // between states still leaves a reader guessing which state produced the name they heard.
      aria-pressed={setAside}
      aria-label={refused === true ? `${why} The last ask was refused` : why}
      title={why}
      aria-busy={busy === true || undefined}
      // aria-disabled rather than the real attribute, which would drop the control out of the tab
      // order mid-action and strand a keyboard user on the body.
      aria-disabled={busy === true || undefined}
      onClick={() => {
        if (busy !== true) {
          onAsk()
        }
      }}
      style={{
        ...CORNER_BUTTON,
        ...(setAside ? HELD : null),
        ...(refused === true ? CORNER_REFUSED : null),
        ...(busy === true ? CORNER_BUSY : null),
      }}
    >
      {/* DRAWN, NOT TYPED, and a MIRROR PAIR, which is the argument for drawing them rather than
          reaching for U+23ED and U+23EE. The reversal is the message: whatever this button did, the
          same button undoes, and two marks that are each other's reflection about the centre line
          say that before any tooltip is read.

          An arrow into a bar rather than a pause or a cross. A pause says stopped and a cross says
          dropped, and neither is what happens: the bump goes to the END of the run. Skip-to-end is
          the one common mark that means exactly that.

          Reflected about x=8, path for path, so the two weigh the same in the corner. Stroke 1.7 to
          match the rerun arrow beside it; the head is filled because a stroked head at 16px closes
          up into a blob. */}
      <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false" style={CORNER_MARK}>
        {setAside ? (
          <>
            <path d="M13.5 8H6.8" fill="none" stroke="currentColor" strokeWidth="1.7"
              strokeLinecap="round" />
            <path d="M7.2 4.8L3.6 8L7.2 11.2Z" fill="currentColor" />
            <path d="M2 4V12" fill="none" stroke="currentColor" strokeWidth="1.7"
              strokeLinecap="round" />
          </>
        ) : (
          <>
            <path d="M2.5 8H9.2" fill="none" stroke="currentColor" strokeWidth="1.7"
              strokeLinecap="round" />
            <path d="M8.8 4.8L12.4 8L8.8 11.2Z" fill="currentColor" />
            <path d="M14 4V12" fill="none" stroke="currentColor" strokeWidth="1.7"
              strokeLinecap="round" />
          </>
        )}
      </svg>
    </button>
  )
}

/**
 * THE HELD TONE IS THE ONE THE CORPUS ALREADY USES for a bump that is not going anywhere for a
 * reason that is nobody's fault, and it is neither the danger red a refusal wears nor the tertiary
 * grey of a control at rest. Reusing the token rather than picking a colour keeps the one place a
 * state becomes a colour in domain.css.
 */
const HELD: Style = { color: 'var(--state-blocked-dependency)' }

/** Quieter than the explanation above it, because it is evidence rather than instruction. */
const REASON: Style = { marginTop: '2px', color: 'var(--text-tertiary)', whiteSpace: 'pre-wrap' }
