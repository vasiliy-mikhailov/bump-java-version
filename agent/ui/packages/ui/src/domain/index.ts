/**
 * THE DOMAIN: everything that knows what a bump, a verdict or a chain step is.
 *
 * Each of these maps this tool's vocabulary onto a primitive. `VerdictPill` is the only place a
 * verdict becomes a colour; `ChainStrip` is the only place a role does. Keeping those mappings here,
 * rather than letting a payload carry a tone, is what makes them testable.
 */

export { BumpTable, type BumpTableProps } from './BumpTable'
export { bomTotals, type BomTotals } from './boms'
export { cveTotals, type CveTotals } from './cves'
export { ChainStrip, type ChainStripProps } from './ChainStrip'
export { EventFeed, type EventFeedProps } from './EventFeed'
export { PackageTable, collapse, type PackageTableProps } from './PackageTable'
export { PipelineMark, type PipelineMarkProps } from './PipelineMark'
export {
  pipelineOf,
  type Pipeline,
  type PipelineStamp,
  type StampedBump,
} from './pipeline'
export { CARDS, PromptCard, type PromptCardProps } from './PromptCard'
export { lanesOf, platformOf, stemOf, type Lanes, type LaneCell, type LaneRow } from './lanes'
export {
  CORNER,
  CORNER_BUSY,
  CORNER_BUTTON,
  CORNER_MARK,
  CORNER_REFUSED,
  PageHeader,
  type Crumb,
  type PageHeaderProps,
} from './PageHeader'
export { SecurityDelta, type SecurityDeltaProps } from './SecurityDelta'
export {
  ASIDE_WHY,
  BACK_WHY,
  SetAsideButton,
  SetAsideNote,
  type SetAsideButtonProps,
  type SetAsideNoteProps,
} from './SetAside'
export { VerdictPill, type VerdictPillProps } from './VerdictPill'
