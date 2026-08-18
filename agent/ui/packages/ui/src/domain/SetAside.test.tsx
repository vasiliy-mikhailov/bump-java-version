import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ASIDE_WHY, BACK_WHY, SetAsideButton, SetAsideNote } from './SetAside'

/**
 * THE COPY IS THE FEATURE, so it is what these assert.
 *
 * A control that frees a lane is one click away from reading as a control that abandons a
 * repository, and this corpus has lost sweep time to both halves of that mistake: lanes held by a
 * bump going nowhere because nobody dared touch it, and a reader who assumed a set-aside bump would
 * never run again. Three claims have to survive any future rewording, and a test that only checked
 * a button rendered would let all three go.
 */
describe('setting a bump aside', () => {
  it('says the lane is freed rather than the bump dropped', () => {
    render(<SetAsideButton setAside={false} onAsk={() => undefined} />)

    const said = screen.getByRole('button').getAttribute('title') ?? ''
    expect(said).toContain('lane')
    // NOT DROPPED is the half a reader gets wrong, and it costs a repository that should have been
    // set aside hours in a lane nobody would free.
    expect(said).toContain('not dropped')
    expect(said).toContain('corpus')
  })

  it('says it runs at the end rather than never', () => {
    render(<SetAsideButton setAside={false} onAsk={() => undefined} />)

    // run.sh clears what was set aside once it is all that remains, so "postponed" is a place in
    // the order and not a verdict. A button that did not say so would read as a delete.
    expect(screen.getByRole('button').getAttribute('title')).toContain('runs at the end')
  })

  it('says it is reversible from the same button, in both of its states', () => {
    const { rerender } = render(<SetAsideButton setAside={false} onAsk={() => undefined} />)
    expect(screen.getByRole('button').getAttribute('title')).toContain('same button brings it back')

    rerender(<SetAsideButton setAside onAsk={() => undefined} />)
    expect(screen.getByRole('button').getAttribute('title')).toContain('same button sets it aside')
  })

  it('looks different when the bump is already set aside', () => {
    // OR THE BUTTON IS A COIN FLIP. Same mark in both states and a reader cannot tell whether a
    // click holds this bump back or releases it, which is the one thing they need before pressing.
    const { rerender } = render(<SetAsideButton setAside={false} onAsk={() => undefined} />)
    const loose = screen.getByRole('button')
    expect(loose.getAttribute('aria-pressed')).toBe('false')
    expect(loose.getAttribute('title')).toBe(ASIDE_WHY)
    const looseMark = loose.querySelector('path')?.getAttribute('d')

    rerender(<SetAsideButton setAside onAsk={() => undefined} />)
    const held = screen.getByRole('button')
    expect(held.getAttribute('aria-pressed')).toBe('true')
    expect(held.getAttribute('title')).toBe(BACK_WHY)
    expect(held.getAttribute('style')).toContain('var(--state-blocked-dependency)')
    expect(held.querySelector('path')?.getAttribute('d')).not.toBe(looseMark)
  })

  it('asks once per click, and not at all while an ask is in flight', () => {
    const ask = vi.fn()
    const { rerender } = render(<SetAsideButton setAside={false} onAsk={ask} />)
    fireEvent.click(screen.getByRole('button'))
    expect(ask).toHaveBeenCalledTimes(1)

    rerender(<SetAsideButton setAside={false} busy onAsk={ask} />)
    fireEvent.click(screen.getByRole('button'))
    expect(ask).toHaveBeenCalledTimes(1)
  })

  it('stays in the tab order while it is busy, and says so instead', () => {
    // The real disabled attribute would drop the control mid-action and strand a keyboard user on
    // the body, which is the one interaction a mouse never notices going wrong.
    render(<SetAsideButton setAside={false} busy onAsk={() => undefined} />)

    const button = screen.getByRole('button')
    expect(button.hasAttribute('disabled')).toBe(false)
    expect(button.getAttribute('aria-busy')).toBe('true')
    expect(button.getAttribute('aria-disabled')).toBe('true')
  })

  it('wears a refusal without being taken away by it', () => {
    render(<SetAsideButton setAside={false} refused onAsk={() => undefined} />)

    const button = screen.getByRole('button')
    expect(button.getAttribute('style')).toContain('border-color: var(--danger)')
    // A reader who cannot see the red border still hears that the last ask went nowhere.
    expect(button.getAttribute('aria-label')).toContain('refused')
  })

  it('explains the state in prose beside the control, not in colour alone', () => {
    render(<SetAsideNote />)

    const note = screen.getByRole('status').textContent ?? ''
    expect(note).toContain('Set aside.')
    expect(note).toContain('work that can progress')
    expect(note).toContain('still in the corpus')
    expect(note).toContain('runs at the end')
    expect(note).toContain('brings it back')
  })

  it('shows the reason on the marker when the server read one back', () => {
    // Most markers are the supervisor's, written off a digest of the whole sweep, and its sentence
    // is the answer to the question a reader finding a held bump actually has.
    render(<SetAsideNote why="four hours in troubleshoot with no diff" />)

    expect(screen.getByRole('status').textContent).toContain(
      'four hours in troubleshoot with no diff',
    )
  })

  it('says nothing extra when there is no reason to read', () => {
    // The listing that tells the page a bump is held carries slugs and not reasons, so an empty
    // string is the ordinary case on page load and must not draw an empty line.
    render(<SetAsideNote why="" />)

    expect(screen.getByRole('status').querySelector('div')).toBeNull()
  })
})
