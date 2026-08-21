'use client'

import { useId } from 'react'
import { Account } from '../primitives'
import type { Style } from '../primitives'

export type ForgetKeyChoiceProps = {
  /**
   * WHERE THE KEY IN FORCE CAME FROM, in the server's own words: "this page", "the environment", or
   * "" when there is no key anywhere to have a source.
   *
   * A plain string rather than a union, matching `KeyStatus` next to it, because the set of places
   * a key can come from is a fact about a deployment. Anything that is not literally "this page"
   * leaves this control inert, which is the safe direction: guessing the other way arms a
   * destructive checkbox on the strength of a field the server declined to answer.
   */
  keySource: string
  checked: boolean
  onChange: (v: boolean) => void
}

const ROW: Style = { display: 'flex', gap: '.4rem', alignItems: 'baseline', margin: '.5rem 0 0' }

const LABEL: Style = { fontSize: '.8rem', color: 'var(--text-secondary)' }

const LABEL_OFF: Style = { ...LABEL, color: 'var(--text-tertiary)' }

/**
 * "Forget the key saved here and go back to the environment's."
 *
 * THE SIBLING'S COMPONENT, AND THE REASON IT HAD TO COME OVER WITH THE BLANK RULE. This page used
 * to refuse a blank save outright, on the stated grounds that a save which silently did nothing is
 * its own lie, and that was correct while there was nothing else to drop a saved key with. There is
 * now, so blank goes back to meaning leave it alone and this is the only way to unset one. The two
 * halves arrived together deliberately: taking the blank rule without this checkbox would leave a
 * page that can set a key and can never take it back.
 *
 * IT TRAVELS WITH THE SAVE, WHICH IS WHY IT IS CONTROLLED HERE. `checked` is the screen's state and
 * goes into the same request as the key, and the server applies the removal AFTER the value, so
 * forgetting wins over a key sent in the same request rather than racing it.
 *
 * IT IS NOT A BUTTON. This is a choice made before saving, not an action of its own, and arming it
 * would be a confirmation for something nobody has committed to yet. The save is the confirmation.
 *
 * WITH NO KEY OF OUR OWN THERE IS NOTHING TO FORGET, so it is disabled and says why rather than
 * being hidden: a control that vanishes leaves the reader wondering whether they imagined it, and
 * the reason it is inert is the useful half of the sentence.
 */
export function ForgetKeyChoice({ keySource, checked, onChange }: ForgetKeyChoiceProps) {
  const id = useId()
  const nothingSaved = keySource !== 'this page'
  return (
    <div>
      <div style={ROW}>
        <input
          id={id}
          type="checkbox"
          name="forget_key"
          checked={checked}
          disabled={nothingSaved}
          onChange={(event) => onChange(event.currentTarget.checked)}
        />
        <label htmlFor={id} style={nothingSaved ? LABEL_OFF : LABEL}>
          forget the key saved on this page
        </label>
      </div>
      {nothingSaved ? (
        <Account quiet>
          nothing is saved here, the agents are using the environment&rsquo;s key, and this page
          cannot unset that
        </Account>
      ) : null}
    </div>
  )
}
