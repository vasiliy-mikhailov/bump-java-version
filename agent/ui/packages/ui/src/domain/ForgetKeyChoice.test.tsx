import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ForgetKeyChoice } from './ForgetKeyChoice'

/**
 * THE ONLY WAY TO DROP A KEY SAVED ON THIS PAGE, so it has to be the only way in both directions.
 *
 * The blank rule moved with this checkbox: an emptied key box now means leave it alone, on the
 * grounds that a browser which clears the field must not be able to unset the credential. That
 * trade only holds while something else can unset it deliberately, which is this. So what is
 * asserted is the pair: that the control is live exactly when there is a key of our own to drop,
 * and that it is inert and says why when there is not, rather than offering to unset something this
 * page cannot reach.
 */
describe('forgetting the key saved on this page', () => {
  it('is offered when the key in force is this page’s', () => {
    const chose = vi.fn()
    render(<ForgetKeyChoice keySource="this page" checked={false} onChange={chose} />)

    const box = screen.getByRole('checkbox')
    expect(box.hasAttribute('disabled')).toBe(false)
    fireEvent.click(box)
    expect(chose).toHaveBeenCalledWith(true)
  })

  it('is inert when the key is the environment’s, and says that rather than disappearing', () => {
    render(<ForgetKeyChoice keySource="the environment" checked={false} onChange={() => undefined} />)

    // A control that vanishes leaves the reader wondering whether they imagined it. The reason it
    // is inert is the useful half of the sentence.
    expect(screen.getByRole('checkbox').hasAttribute('disabled')).toBe(true)
    expect(screen.getByText(/cannot unset that/)).toBeTruthy()
  })

  it('stays inert when the server declined to name a source at all', () => {
    // "" is what the server sends when there is no key anywhere. Reading it as "the environment"
    // would be a guess; reading it as "this page" would arm a destructive control on the strength
    // of a field the record refused to answer.
    render(<ForgetKeyChoice keySource="" checked={false} onChange={() => undefined} />)

    expect(screen.getByRole('checkbox').hasAttribute('disabled')).toBe(true)
  })

  it('travels with the save rather than firing on its own', () => {
    // In the sibling this checkbox once sat outside the form, so the field it posts was never sent
    // and the branch that handles it was unreachable from the page that exists to reach it. It is
    // controlled here for that reason: it carries no request of its own, only state the save reads.
    const chose = vi.fn()
    render(<ForgetKeyChoice keySource="this page" checked={true} onChange={chose} />)

    const box = screen.getByRole('checkbox') as HTMLInputElement
    expect(box.checked).toBe(true)
    expect(box.getAttribute('name')).toBe('forget_key')
  })
})
