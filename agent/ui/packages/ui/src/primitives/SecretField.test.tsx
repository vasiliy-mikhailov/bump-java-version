import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SecretField } from './SecretField'

// FABRICATED, AND IT HAS TO BE. A fixture that borrows the shape of a real key by pasting the
// front of one puts credential material in a public repository, where a test file is the last
// place anybody thinks to look for it.
const KEY = 'sk-testonly-000102030405060708090a0b0c0d0e0f'

/** Whatever the browser is pretending to be this time, on the instance so the prototype is spared. */
function clipboard(writeText?: (v: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    value: writeText === undefined ? undefined : { writeText },
    configurable: true,
  })
}

/**
 * A FIELD THAT SHOWS A CREDENTIAL HAS TO EARN IT.
 *
 * This page shows the model key because the reveal and copy buttons cannot work otherwise, which is
 * a decision recorded in the Java. What the decision buys is exactly these two buttons, so these
 * are what is asserted: that the value is masked until somebody asks, and that copy hands over the
 * whole key rather than the row of dots a reader would otherwise select by hand and get wrong.
 */
describe('the key field on the settings page', () => {
  it('masks the key until a reader asks to see it, and hides it again after', () => {
    render(<SecretField label="API key" value={KEY} onChange={() => undefined} />)
    const field = screen.getByLabelText('API key')

    // Over a shoulder, and in every screenshot of this page, until somebody chooses otherwise.
    expect(field.getAttribute('type')).toBe('password')

    fireEvent.click(screen.getByTitle('show'))
    expect(screen.getByLabelText('API key').getAttribute('type')).toBe('text')

    fireEvent.click(screen.getByTitle('hide'))
    expect(screen.getByLabelText('API key').getAttribute('type')).toBe('password')
  })

  it('copies the key itself rather than what the mask shows', async () => {
    const wrote = vi.fn().mockResolvedValue(undefined)
    clipboard(wrote)
    render(<SecretField label="API key" value={KEY} onChange={() => undefined} />)

    fireEvent.click(screen.getByTitle('copy'))

    await waitFor(() => expect(wrote).toHaveBeenCalledWith(KEY))
    // AND SAYS SO. A copy button that looks identical before and after leaves a reader pressing it
    // twice and pasting into the wrong window to find out whether it worked.
    await screen.findByText('copied')
  })

  it('refuses to offer a copy button the browser will not honour', () => {
    // The clipboard exists only in a secure context, so over plain http the button is dead. A dead
    // button that looks alive is worse than a disabled one: the reader believes they have the key.
    clipboard(undefined)
    render(<SecretField label="API key" value={KEY} onChange={() => undefined} />)

    const copy = screen.getByTitle('copying needs https')
    expect((copy as HTMLButtonElement).disabled).toBe(true)
  })

  it('reports every keystroke, so a save cannot send the value from before the last one', () => {
    const typed = vi.fn()
    render(<SecretField label="API key" value="" onChange={typed} />)

    fireEvent.change(screen.getByLabelText('API key'), { target: { value: KEY } })

    expect(typed).toHaveBeenCalledWith(KEY)
  })

  it('carries its blank policy under the field, because the two tools disagree about it', () => {
    // The sibling leaves a blank box alone; this page refuses the save. Whichever it is has to be
    // written where somebody about to clear the box will read it.
    render(
      <SecretField
        label="API key"
        value={KEY}
        onChange={() => undefined}
        hint="A blank save is refused rather than clearing the key."
      />,
    )

    expect(screen.getByText(/blank save is refused/)).not.toBeNull()
  })

  it('does not offer the key to the browser to remember', () => {
    render(<SecretField label="API key" value={KEY} onChange={() => undefined} />)

    // A keychain entry is a copy of the credential somewhere this page cannot reach and nobody
    // will think to clear when the key is rotated.
    expect(screen.getByLabelText('API key').getAttribute('autocomplete')).toBe('off')
  })
})
