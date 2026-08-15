/**
 * THE DOMAIN: everything that knows what a bump, a verdict or a chain step is.
 *
 * Each of these maps this tool's vocabulary onto a primitive. `VerdictPill` is the only place a
 * verdict becomes a colour; `ChainStrip` is the only place a role does. Keeping those mappings here,
 * rather than letting a payload carry a tone, is what makes them testable.
 */

export { BumpTable, type BumpTableProps } from './BumpTable'
export { cveTotals, type CveTotals } from './cves'
export { ChainStrip, type ChainStripProps } from './ChainStrip'
export { EventFeed, type EventFeedProps } from './EventFeed'
export { PackageTable, collapse, type PackageTableProps } from './PackageTable'
export { CARDS, PromptCard, type PromptCardProps } from './PromptCard'
export { CORNER, PageHeader, type Crumb, type PageHeaderProps } from './PageHeader'
export { SecurityDelta, type SecurityDeltaProps } from './SecurityDelta'
export { VerdictPill, type VerdictPillProps } from './VerdictPill'
