import { useState } from 'react'

/**
 * WHAT A REFUSAL SAYS WHEN THE SERVER WOULD NOT SAY.
 *
 * A control that reports nothing after a click that changed nothing is the worst of the four
 * states: the reader cannot tell a refusal from a click that missed. Both endpoints on this site
 * may answer with a refusal and no reason, so the sentence is here rather than typed out beside
 * each of them, where the two copies had already begun to matter — one page would have said
 * "declined" and the other nothing at all.
 */
export const NO_REASON = 'the server declined without saying why'

/**
 * A REFUSAL AND AN OUTAGE HAVE TO READ DIFFERENTLY, which is the whole reason this prefix exists.
 *
 * Both endpoints answer a refusal with 200 and a body, so the only thing that ever reaches the
 * catch is the network, the page being served without its api behind it, or a body that would not
 * parse. A wrong slug and an unreachable server are different problems with different next moves,
 * and a control that renders both as "it did not work" sends a reader to look at the wrong one.
 */
export const REQUEST_FAILED = 'the request itself failed: '

/**
 * WHAT THE SERVER'S ANSWER MEANT, decided by the caller because only the caller knows the endpoint.
 *
 * `landed` IS READ OFF THE STATE THE SERVER REPORTS, NOT OFF THE PRESENCE OF AN ERROR, and the two
 * controls this replaces disagreed about that. The rerun control asked "did you queue it" and got a
 * boolean back. The set-aside control compares what is on disk NOW against what was asked for,
 * because its endpoint answers with the state it read back rather than an echo of the request: an
 * ask that did not move it is a refusal whether or not a reason came with it. Trusting an `error`
 * field alone would call a silent no-op a success and leave the control showing the state the
 * reader asked for rather than the one the launcher will see.
 */
export type Landing = {
  /** Did the ask actually change anything? */
  landed: boolean
  /** The server's own words for why it did not. Absent is ordinary rather than wrong. */
  why?: string | undefined
}

export type AskHow<In, Out> = {
  /** Make the ask. The caller owns the endpoint; this owns what happens around it. */
  send: (input: In) => Promise<Out>
  /** Read the answer: did it land, and if not, what did the server say. */
  read: (answer: Out, input: In) => Landing
  /**
   * EVERY ANSWER, LANDED OR REFUSED, because a refusal still carries facts.
   *
   * The postpone endpoint replies with what is on disk however it decided, so a page that only
   * looked at answers it liked would keep showing a state the server has already contradicted.
   * Not called when the request itself failed: there is no answer then, only an error.
   */
  onAnswer?: (answer: Out, input: In) => void
}

export type Ask<In> = {
  /**
   * HOW MANY ASKS HAVE BEEN MADE. Only ever goes up, so a control animating one turn per ask never
   * rewinds when one is refused, and a refused ask is still visibly an ask.
   */
  asks: number
  /** An ask is in flight. */
  busy: boolean
  /** The last ask changed something. False again the moment another ask starts. */
  landed: boolean
  /** Why the last ask changed nothing. Empty when it did, and before anybody has asked. */
  refused: string
  ask: (input: In) => void
}

/**
 * THE FOUR STATES OF ASKING A SERVER TO DO SOMETHING: idle, busy, done, refused.
 *
 * Two controls in the bump page's corner had this written out longhand, and they got it subtly
 * differently in three places, all of which are the refusal handling:
 *
 * <ol>
 *   <li>What counts as a refusal. One read a boolean off the answer; the other compared the state
 *       the server read back against the state that was asked for. Both are right for their own
 *       endpoint, which is why `read` is the caller's and everything else is not.</li>
 *   <li>What is cleared when a new ask starts. Both cleared the refusal, and they had to: a control
 *       still wearing the last refusal while a fresh ask is in flight is lying about the present.
 *       Only one of them cleared its "it worked" flag as well, and the other could briefly show
 *       done and busy at once.</li>
 *   <li>What a thrown request says. Both prefixed it, by hand, with the same sentence — which is
 *       to say the sentence was one edit away from being two sentences.</li>
 * </ol>
 *
 * NOTHING HERE IS DISABLED. That belongs to the control: `aria-disabled` rather than the attribute,
 * because the real one drops a button out of the tab order mid-action and strands a keyboard user
 * on the document body. This hook only says whether an ask is in flight; both controls check it
 * before calling `ask` again, and a second call while busy is the caller's to refuse.
 */
export function useAsk<In, Out>(how: AskHow<In, Out>): Ask<In> {
  const [state, setState] = useState({ asks: 0, busy: false, landed: false, refused: '' })

  // Not memoised, and deliberately: it closes over THIS render's `how`, whose `send` and `onAnswer`
  // close over this render's state in turn. A callback held stable across renders would be asking
  // with a slug the page had already navigated away from.
  const ask = (input: In) => {
    setState((s) => ({ asks: s.asks + 1, busy: true, landed: false, refused: '' }))
    how
      .send(input)
      .then((answer) => {
        const landing = how.read(answer, input)
        setState((s) => ({
          ...s,
          busy: false,
          landed: landing.landed,
          refused: landing.landed ? '' : (landing.why ?? NO_REASON),
        }))
        how.onAnswer?.(answer, input)
      })
      .catch((e: Error) =>
        setState((s) => ({ ...s, busy: false, refused: `${REQUEST_FAILED}${e.message}` })),
      )
  }

  return { ...state, ask }
}
