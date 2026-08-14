import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { STRIP } from './primitives'

/**
 * TWO TOOLS BEHIND ONE NAV HAVE TO LOOK LIKE ONE APPLICATION.
 *
 * The palette was never the hard part — `tokens.css` is copied verbatim and a diff catches it. What
 * makes a zone read as a different product is the things nobody thinks to check: the typeface, where
 * the page starts, how a summary number is set. A reader crossing the boundary registers those
 * before reading a single word.
 *
 * So the values the sibling tool fixed are asserted here rather than left as a convention someone
 * remembers. They are ALLOWED to change — but deliberately, in both repositories, not because a
 * component was written in a hurry.
 *
 * This is also the evidence for what spec 17 said to wait for: with two tools, the overlap is a fact
 * rather than a guess, and everything asserted below is a candidate to lift into one package both
 * import instead of both copying.
 */
describe('the house style, shared with fix-java-svace-markers', () => {
  const globals = readFileSync(
    join(import.meta.dirname, '../../../apps/web/app/globals.css'),
    'utf8',
  )

  it('sets the sibling’s type on body, declaration for declaration', () => {
    // A zone that picked its own font would be recognisable as a different product from across the
    // room, which is louder than any palette difference.
    expect(globals).toContain('font: 13px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace')
  })

  it('paints the portal’s canvas rather than white', () => {
    expect(globals).toContain('background: var(--bg-canvas)')
    expect(globals).toContain('color: var(--text-primary)')
  })

  it('imports the portal tokens before anything of its own', () => {
    const tokens = globals.indexOf('tokens.css')
    const domain = globals.indexOf('domain.css')
    expect(tokens).toBeGreaterThan(-1)
    // The domain palette is expressed against the portal's, so it has to be able to see it.
    expect(domain).toBeGreaterThan(tokens)
  })

  it('keeps the portal tokens unmodified', () => {
    const tokens = readFileSync(join(import.meta.dirname, 'tokens.css'), 'utf8')
    // Copied verbatim, both themes, switched by `.dark` on the root — the way the portal does it.
    // A value "improved" here is how a portal of microfrontends starts feeling like four websites.
    expect(tokens).toContain('--bg-canvas:')
    expect(tokens).toContain('--accent-primary: #E30613')
    expect(tokens).toContain('.dark')
  })

  it('starts the page where the sibling starts it', () => {
    // 24px, and the tables and headers all use it. Two tools that inset their content differently
    // read as two sites the moment a reader crosses the boundary.
    expect(STRIP.padding).toBe('14px 24px')
  })

  it('has no centred column, because the sibling has none', () => {
    const layout = readFileSync(
      join(import.meta.dirname, '../../../apps/web/app/layout.tsx'),
      'utf8',
    )
    expect(layout).not.toContain('maxWidth')
  })
})
