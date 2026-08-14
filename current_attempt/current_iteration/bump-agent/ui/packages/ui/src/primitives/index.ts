/**
 * THE PRIMITIVES: everything the dashboard is built from that does not know what a bump is.
 *
 * A `Pill` takes a tone, not a verdict. A `TabRow` takes hrefs, not a slug. That line is why these
 * are testable at all, and it is what stops a `Pill` growing a `verdict` prop the next time somebody
 * is in a hurry.
 *
 * Several of these are the sibling tool's shapes at its measurements — `Tally`, `ProgressBar`, the
 * strip they sit in. That is deliberate and is the thing spec 17 predicted: with two tools, the
 * overlap is finally a fact rather than a guess, and these are the first candidates to lift into a
 * package both import instead of both copying.
 */

export { CodeBlock, type CodeBlockProps } from './CodeBlock'
export { Disclosure, type DisclosureProps } from './Disclosure'
export { EmptyNote, type EmptyNoteProps } from './EmptyNote'
export { FIELD, LabeledField, READONLY, type LabeledFieldProps } from './LabeledField'
export { Pill, type PillProps, type PillTone } from './Pill'
export { ProgressBar, type ProgressBarProps } from './ProgressBar'
export {
  RelativeTime,
  duration,
  relative,
  spellMinutes,
  type RelativeTimeProps,
} from './RelativeTime'
export { SaveRow, type SaveRowProps } from './SaveRow'
export { SettingCard, type SettingCardProps } from './SettingCard'
export { TabRow, type TabItem, type TabRowProps } from './TabRow'
export { STRIP, Tally, type TallyProps } from './Tally'
export { TextFold, type TextFoldProps } from './TextFold'
export type { Style } from './style'
