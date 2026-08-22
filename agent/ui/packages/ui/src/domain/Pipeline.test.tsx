import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BumpTable } from './BumpTable'
import { pipelineOf, type PipelineStamp, type StampedBump } from './pipeline'

/**
 * TWO BUMPS THAT DISAGREE, AND NO WAY TO TELL WHY.
 *
 * A sweep runs for a fortnight and the harness changes under it: on one day this month it was
 * deployed seven times, and three generations of prompt were live in the same sweep at once,
 * because a running lane keeps the image it started with. Every row said what happened and nothing
 * about what produced it, so a reader comparing two bumps could not tell a difference in the
 * repository from a difference in the program that bumped it.
 *
 * The cell has to answer two questions, and they want different amounts of room: "were these two
 * produced by the same pipeline" is a glance down a column, so it is one token; "which pipeline was
 * it" is a hover, so the four fields are there as recorded.
 *
 * THE TOKEN IS THE WHOLE IDENTITY, which is the property these tests are really guarding. Anything
 * that can make two runs behave differently has to reach the printed string, or the glance is
 * confidently wrong. That rules out showing the commit alone: prompt and bill-of-materials edits
 * are made from the settings page, live outside the image, and change nothing a commit can see.
 */
const STAMP: PipelineStamp = {
  commit: '63a5f296',
  image: 'sha256:7439b8dceb',
  prompts: '5ed4079d',
  boms: 'e1cc07d3',
}

/**
 * A row, unstamped unless the caller says otherwise.
 *
 * The four fields are spelled out as null rather than left off, because that is what the server
 * sends for a bump that settled before the stamp existed: the key is present and its value is
 * null, and "absent" and "null" have to mean the same thing here or the common case renders wrong.
 */
const bump = (slug: string, stamp: PipelineStamp = {}): StampedBump => ({
  slug,
  repo: slug,
  sha: '0f1e2d3',
  from: 17,
  to: 21,
  verdict: 'PASS',
  because: null,
  round: null,
  baselineGreen: true,
  gateGreen: true,
  preTests: 148,
  cvesBefore: 12,
  cvesAfter: 3,
  startedAt: 1_000,
  at: 2_000,
  events: 44,
  humanMinutes: null,
  bomMet: null,
  bomMissed: null,
  bomMetBefore: null,
  bomMissedBefore: null,
  bomPairApplied: null,
  bomPairMissedBefore: null,
  bomPairMissedAfter: null,
  bomOutstanding: null,
  commit: null,
  image: null,
  prompts: null,
  boms: null,
  ...stamp,
})

/** What the rightmost cell of each row actually reads, which is what a reader compares. */
function pipelineCells(container: HTMLElement): string[] {
  // `Array.from` rather than a spread: the DOM.Iterable lib is not on, so spreading a NodeList is
  // a type error here even though it runs.
  return Array.from(container.querySelectorAll('tbody tr')).map(
    (row) => row.querySelector('td:last-child')?.textContent ?? '',
  )
}

describe('the pipeline stamp', () => {
  it('reads the same for two bumps produced by the same pipeline', () => {
    const { container } = render(
      <BumpTable bumps={[bump('a', STAMP), bump('b', { ...STAMP })]} hrefFor={(s) => `/${s}`} />,
    )

    const [first, second] = pipelineCells(container)
    expect(first).toBe(second)
    expect(first).toContain('63a5f296')
  })

  it('reads differently when a prompt was edited, on the same commit and the same image', () => {
    // THE CASE THE COMMIT CANNOT SEE, and the reason this is not just a short sha. An override
    // saved from the settings page takes effect on the next lane to start, so two lanes of one
    // deployment can be handed different prompts. A column that called those the same pipeline
    // would be wrong exactly when a reader most needs it to be right.
    const { container } = render(
      <BumpTable
        bumps={[bump('a', STAMP), bump('b', { ...STAMP, prompts: '11aa22bb' })]}
        hrefFor={(s) => `/${s}`}
      />,
    )

    const [first, second] = pipelineCells(container)
    expect(first).not.toBe(second)
    // Still the same commit, and still saying so: the difference is not that they came from
    // different code, and the cell must not suggest it did.
    expect(second).toContain('63a5f296')
  })

  it('reads differently when a bill of materials was edited', () => {
    expect(pipelineOf({ ...STAMP, boms: 'ffff0000' })?.label).not.toBe(pipelineOf(STAMP)?.label)
  })

  it('reads differently when the same commit was deployed twice', () => {
    // Two builds of one commit are two images, and a lane keeps the image it started with.
    expect(pipelineOf({ ...STAMP, image: 'sha256:00ff11ee22' })?.label).not.toBe(
      pipelineOf(STAMP)?.label,
    )
  })

  it('says unstamped, which is neither a fault nor an empty cell', () => {
    // THE COMMON CASE FOR A WHILE. Every bump that settled before the stamp existed has none and
    // never will, so this column is mostly this word. A dash would file it with "nothing was
    // measured here", which is a different gap, and nothing at all would read as a broken page.
    render(<BumpTable bumps={[bump('old')]} hrefFor={(s) => `/${s}`} />)

    const said = screen.getByText('unstamped')
    expect(said).toBeTruthy()
    expect(said.getAttribute('title')).toContain('before the pipeline stamp existed')
  })

  it('treats a null stamp as unstamped, because the server sends null rather than omitting it', () => {
    expect(pipelineOf({ commit: null, image: null, prompts: null, boms: null })).toBeNull()
    expect(pipelineOf({})).toBeNull()
  })

  it('marks an image built from a tree that was not clean', () => {
    // A dirty build is not the commit it names, so two of them can differ with nothing to show for
    // it but their image ids. The mark is on the label rather than only in the hover for that
    // reason: a reader glancing at two identical shas has to see that the sha is not the answer.
    const dirty = pipelineOf({ ...STAMP, commit: '63a5f296-dirty' })

    expect(dirty?.dirty).toBe(true)
    expect(dirty?.head).toBe('63a5f296+')
    expect(dirty?.label).not.toBe(pipelineOf(STAMP)?.label)
    expect(dirty?.detail).toContain('uncommitted changes')
  })

  it('keeps all four fields as recorded, on the hover', () => {
    // The token is a summary and a reader who wants the image id wants the image id, not a fold of
    // it. Every field travels, including the ones the label compresses.
    render(<BumpTable bumps={[bump('a', STAMP)]} hrefFor={(s) => `/${s}`} />)

    const hover = screen.getByTitle(/commit 63a5f296/).getAttribute('title') ?? ''
    expect(hover).toContain('image sha256:7439b8dceb')
    expect(hover).toContain('prompts 5ed4079d')
    expect(hover).toContain('boms e1cc07d3')
  })

  it('names the fields it does not have rather than leaving a gap in the hover', () => {
    const partial = pipelineOf({ commit: '63a5f296' })

    expect(partial?.label).toBe('63a5f296')
    expect(partial?.detail).toContain('image not recorded')
    expect(partial?.detail).toContain('prompts not recorded')
  })

  it('still identifies a run whose image was built outside deploy.sh', () => {
    // No git behind the build, so `unknown` is what was stamped and what is shown. The hashes still
    // separate two such runs, which is the entire job of the second half of the label.
    const one = pipelineOf({ commit: '', image: 'sha256:aa', prompts: '1', boms: '2' })
    const two = pipelineOf({ commit: '', image: 'sha256:bb', prompts: '1', boms: '2' })

    expect(one?.head).toBe('unknown')
    expect(one?.label).not.toBe(two?.label)
  })
})
