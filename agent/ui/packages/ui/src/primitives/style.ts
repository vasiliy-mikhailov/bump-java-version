import type { CSSProperties } from 'react'

/**
 * A style object that may also carry CSS custom properties.
 *
 * `React.CSSProperties` has no index signature, so `{'--tone': …}` is a type error without this. It
 * exists so a component can set a token ONCE and refer to it from three declarations (text,
 * background, border) instead of repeating `var(--state-pass)` three times and letting one drift.
 */
export type Style = CSSProperties & Record<`--${string}`, string | number>
