import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { HEADING, Section } from './Section'

/**
 * WHERE THE GUTTER IS PAID is the only thing the two copies of this component disagreed about, and
 * it is not a preference: it follows from what the block contains.
 */
describe('a heading and the block under it', () => {
  it('insets the whole block when it holds cards or prose', () => {
    const { container } = render(
      <Section title="what it settled as">
        <p>because the gate was green</p>
      </Section>,
    )

    const block = container.querySelector('section')
    expect(block?.getAttribute('style')).toContain('padding: 0px 24px')
    // The heading drops its own inset, because the block already carries it.
    expect(screen.getByRole('heading').getAttribute('style')).toContain('margin: 18px 0px 10px')
  })

  it('insets only the heading when the block holds a full-bleed table', () => {
    // A table's own cells carry the page gutter. Inset a second time it would sit 48px in, while
    // every other table on the site sits at 24.
    const { container } = render(
      <Section title="by package, best outcome first" gutter="heading">
        <table />
      </Section>,
    )

    expect(container.querySelector('section')?.getAttribute('style')).not.toContain('padding')
    expect(screen.getByRole('heading').getAttribute('style')).toContain('margin: 18px 24px 10px')
  })

  it('gives an anchored section the room the browser needs to scroll to it', () => {
    // A link into the middle of a page parks the target flush against the top of the viewport,
    // where its own heading is the first thing cropped.
    const { container } = render(
      <Section title="dependencies" gutter="heading" id="dependencies">
        <table />
      </Section>,
    )

    const block = container.querySelector('section')
    expect(block?.getAttribute('id')).toBe('dependencies')
    expect(block?.getAttribute('style')).toContain('scroll-margin-top: 12px')
  })

  it('leaves the anchor off a section nothing links to', () => {
    const { container } = render(
      <Section title="vulnerabilities">
        <p>none</p>
      </Section>,
    )

    const block = container.querySelector('section')
    expect(block?.hasAttribute('id')).toBe(false)
    expect(block?.getAttribute('style')).not.toContain('scroll-margin-top')
  })

  it('sets every section heading the same way', () => {
    // Four copies of these five declarations existed. They had not drifted yet, which is the only
    // reason nobody had noticed.
    render(<Section title="the record">{null}</Section>)

    const said = screen.getByRole('heading').getAttribute('style') ?? ''
    expect(said).toContain('font-size: 11px')
    expect(said).toContain('text-transform: uppercase')
    expect(said).toContain('color: var(--text-tertiary)')
    expect(said).toContain('font-weight: 500')
    // Read off the object rather than the rendered attribute: the DOM normalises `.06em` to
    // `0.06em`, and the value the source carries is what a second copy of this would drift from.
    expect(HEADING.letterSpacing).toBe('.06em')
  })
})
