import type { BumpSummary } from '@bjv/types'

/**
 * WHICH PIPELINE PRODUCED A ROW, as the settlement recorded it.
 *
 * <p>These four fields are the server's, written on every settled bump and null on every bump that
 * settled before they existed. They are declared here as OPTIONAL and NULLABLE rather than read off
 * `BumpSummary`, so that this package renders the same whether the wire types have caught up or
 * not: a missing field and a null field both mean unstamped, which is the ordinary case.
 */
export type PipelineStamp = {
  /** The commit the image was built from, with a `-dirty` suffix when the tree was not clean. */
  commit?: string | null
  /** The image id the lane actually started from, which is not the tag: every deploy moves that. */
  image?: string | null
  /** Every system prompt for the hop, hashed, settings-page overrides included. */
  prompts?: string | null
  /** Both bill-of-materials lists for the hop, hashed, overrides included. */
  boms?: string | null
}

/** A corpus row with whatever stamp it carries. Rows from before the stamp carry none. */
export type StampedBump = BumpSummary & PipelineStamp

export type Pipeline = {
  /**
   * WHAT THE CELL PRINTS, and the whole point of this module: two bumps produced by the same
   * pipeline print the same string, and two that were not print different ones. Nothing that
   * distinguishes two pipelines is left out of it and hidden in the tooltip.
   */
  label: string
  /** The commit, which is the half a reader can go and look at. `+` when the tree was not clean. */
  head: string
  /** Four characters folding the image and the two hashes, or empty when none were recorded. */
  fold: string
  /** The image was built from uncommitted edits, so its commit does not identify the code. */
  dirty: boolean
  /** Field by field, unabridged, for the hover. */
  detail: string
}

const DIRTY = '-dirty'

/** Trimmed, with null, undefined and blank collapsed into the one thing they mean here: absent. */
function text(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}

/**
 * THE THREE FIELDS A READER CANNOT READ, FOLDED INTO FOUR CHARACTERS.
 *
 * An image id and two SHA-256 prefixes are forty characters of noise in a table column, and a
 * reader comparing two rows is not reading them: they are asking whether the rows are the same.
 * Folding answers that in a width the column can afford, and the commit beside it keeps the half
 * that leads somewhere legible.
 *
 * FNV-1a, deliberately not a cryptographic hash: nothing here is a security claim, the inputs are
 * already digests, and a collision costs a reader one hover. Four characters over the handful of
 * variants that share a commit is ample; over the whole fortnight the commit does the separating.
 */
function fold(parts: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < parts.length; i += 1) {
    h ^= parts.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0').slice(-4)
}

/**
 * The stamp as the page shows it, or null when there is nothing stamped.
 *
 * <p>THE IMAGE ALONE WOULD BE WRONG, and wrong in the expensive direction. Prompt and
 * bill-of-materials edits are made from the settings page and live outside the image, so an
 * identity built from the image would call two runs the same pipeline precisely when one of them
 * had been edited. So the hashes of what the agents were actually handed are part of the label.
 *
 * <p>THE COMMIT ALONE WOULD BE WRONG FOR THE SAME REASON, and additionally because a `-dirty`
 * image was built from a working tree that no commit describes. Two dirty builds of one commit are
 * different pipelines, and they are told apart here by their image ids, not by their commit.
 */
export function pipelineOf(stamp: PipelineStamp): Pipeline | null {
  const commit = text(stamp.commit)
  const image = text(stamp.image)
  const prompts = text(stamp.prompts)
  const boms = text(stamp.boms)
  if (commit === '' && image === '' && prompts === '' && boms === '') {
    return null
  }

  const dirty = commit.endsWith(DIRTY)
  const sha = dirty ? commit.slice(0, -DIRTY.length) : commit
  // `unknown` is deploy.sh's own word for a build with no git behind it, kept rather than invented.
  const head = (sha === '' ? 'unknown' : sha) + (dirty ? '+' : '')
  const rest = image === '' && prompts === '' && boms === ''
    ? ''
    : fold(`${image}\n${prompts}\n${boms}`)

  return {
    label: rest === '' ? head : `${head}·${rest}`,
    head,
    fold: rest,
    dirty,
    detail: [
      `commit ${sha === '' ? 'not recorded' : sha}` +
        (dirty ? ', built from a tree with uncommitted changes' : ''),
      `image ${image === '' ? 'not recorded' : image}`,
      `prompts ${prompts === '' ? 'not recorded' : prompts}`,
      `boms ${boms === '' ? 'not recorded' : boms}`,
    ].join('\n'),
  }
}
