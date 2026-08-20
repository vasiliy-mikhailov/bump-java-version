/**
 * A style object that may also carry CSS custom properties.
 *
 * ONE LINE, AND IT STAYS A FILE. The type itself is `ratchet-ui`'s, where it is
 * `WithTokens<CSSProperties>` and expands to exactly what this used to declare. Keeping the module
 * here means the twenty-odd `import type { Style } from './style'` lines in this package go on
 * working, which is the difference between a re-export and a rename across twenty files.
 */
export type { Style } from 'ratchet-ui/components'
