/**
 * THE PRIMITIVES: everything the dashboard is built from that does not know what a bump is.
 *
 * A `Pill` takes a tone, not a verdict. A `TabRow` takes hrefs, not a slug. That line is why these
 * are testable at all, and it is what stops a `Pill` growing a `verdict` prop the next time somebody
 * is in a hurry.
 *
 * MOST OF THIS DIRECTORY IS NOW ONE IMPORT. `ratchet-ui` ships what both dashboards had written
 * twice: the five of 0.2.0, and in 0.3.0 the whole tranche that was blocked by markup and nothing
 * else. They are re-exported from here rather than imported directly by each screen, so this barrel
 * stays the one public surface and no call site had to move.
 *
 * TWO OF THEM CAME THE OTHER WAY, AND THEY ARE THE FIRST TO DO SO. `CodeBlock` and `Lamp` are the
 * sibling's, taken in 0.4.0 under a rule that had to be written before either could move. This
 * repository's rule one asked that both dashboards wrote the thing AND that the difference was
 * palette rather than behaviour, which means that the moment two versions differ in behaviour the
 * rule excludes itself and hands the case to nothing. `CodeBlock` was declined that way, on a note
 * describing everything the other version does MORE. The amendment: where the versions differ in
 * behaviour, the shared one is the version with call sites. Ours had none, and the settings page
 * beside it wrote the same box inline having dropped the font stack our own component carried.
 *
 * `Lamp` CARRIES NO VOCABULARY AND THAT IS DELIBERATE ON THEIR SIDE. It takes a colour and a whole
 * sentence as props, because the two-lamp component it was lifted out of means something by red and
 * green that this pipeline does not. `BumpTable` supplies both.
 *
 * FIVE OF THEM WERE NEVER A FILE IN THIS REPOSITORY. `Account`, `HumanCost`, `KeyStatus`,
 * `TimeSpent` and `DataTable` are this dashboard's own inline code, extracted; the sibling had
 * already extracted its own and the names are the sibling's, because naming a thing is what the side
 * that extracted it contributed and "took" is a column heading rather than a component name. The
 * behaviour inside each is this repository's, unchanged. Each shared file says which half is which.
 *
 * TWO TAB ROWS LIVE IN THIS BARREL AND THEY ARE DIFFERENT COMPONENTS. `TabRow` is the underline row
 * INSIDE a page, "summary | the record" and the hop tabs on the prompts page. `SectionTabs` is the
 * ruled bar across the TOP of a page, which lights a section with a filled pill. Reaching for
 * whichever one autocompletes first is how a page ends up claiming its subsection is the page.
 */

export { Card, type CardProps } from './Card'
export { Disclosure, type DisclosureProps } from './Disclosure'
export {
  ACCOUNT,
  ACCOUNT_QUIET,
  Account,
  CodeBlock,
  DataTable,
  EmptyNote,
  HEADING,
  HumanCost,
  KeyStatus,
  Lamp,
  Loaded,
  PAGE_GUTTER,
  Pill,
  ProgressBar,
  STRIP,
  Section,
  SectionTabs,
  SettingCard,
  Tally,
  TimeSpent,
  NO_REASON,
  REQUEST_FAILED,
  useAsk,
  type AccountProps,
  type Align,
  type Ask,
  type AskHow,
  type CodeBlockProps,
  type Column,
  type DataTableProps,
  type EmptyNoteProps,
  type HumanCostProps,
  type KeyStatusProps,
  type LampProps,
  type Landing,
  type LoadedProps,
  type PillProps,
  type PillTone,
  type ProgressBarProps,
  type SectionProps,
  type SectionTab,
  type SectionTabsProps,
  type SettingCardProps,
  type TallyProps,
  type TimeSpentProps,
} from 'ratchet-ui/components'
export { FIELD, LabeledField, READONLY, type LabeledFieldProps } from './LabeledField'
export { RelativeTime, duration, relative, spellMinutes, type RelativeTimeProps } from './RelativeTime'
export { SaveRow, type SaveRowProps } from './SaveRow'
export { TabRow, type TabItem, type TabRowProps } from './TabRow'
export { CELL, CELL_NESTED, HEAD, HEAD_NESTED, MONO, ROW, TABLE } from './table'
export { TextFold, type TextFoldProps } from './TextFold'
export type { Style } from './style'
