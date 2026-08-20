/**
 * THE PRIMITIVES: everything the dashboard is built from that does not know what a bump is.
 *
 * A `Pill` takes a tone, not a verdict. A `TabRow` takes hrefs, not a slug. That line is why these
 * are testable at all, and it is what stops a `Pill` growing a `verdict` prop the next time somebody
 * is in a hurry.
 *
 * FIVE OF THESE ARE NO LONGER IN THIS DIRECTORY. `EmptyNote`, `Pill`, `ProgressBar`, `Tally` and
 * the `Style` type come from `ratchet-ui`, which is what spec 17 predicted: with two tools the
 * overlap became a fact rather than a guess, and these are the ones whose two versions differed by
 * palette rather than by behaviour. They are re-exported from here rather than imported directly
 * by each screen, so this barrel stays the one public surface and no call site had to move.
 */

export { Card, type CardProps } from './Card'
export { CodeBlock, type CodeBlockProps } from './CodeBlock'
export { Disclosure, type DisclosureProps } from './Disclosure'
export {
  EmptyNote,
  Pill,
  ProgressBar,
  STRIP,
  Tally,
  type EmptyNoteProps,
  type PillProps,
  type PillTone,
  type ProgressBarProps,
  type TallyProps,
} from 'ratchet-ui/components'
export { FIELD, LabeledField, READONLY, type LabeledFieldProps } from './LabeledField'
export { Loaded, type LoadedProps } from './Loaded'
export {
  RelativeTime,
  duration,
  relative,
  spellMinutes,
  type RelativeTimeProps,
} from './RelativeTime'
export { SaveRow, type SaveRowProps } from './SaveRow'
export { HEADING, PAGE_GUTTER, Section, type SectionProps } from './Section'
export { SettingCard, type SettingCardProps } from './SettingCard'
export { TabRow, type TabItem, type TabRowProps } from './TabRow'
export { CELL, CELL_NESTED, HEAD, HEAD_NESTED, MONO, ROW, TABLE } from './table'
export { TextFold, type TextFoldProps } from './TextFold'
export type { Style } from './style'
export {
  NO_REASON,
  REQUEST_FAILED,
  useAsk,
  type Ask,
  type AskHow,
  type Landing,
} from './useAsk'
