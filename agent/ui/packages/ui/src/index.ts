/**
 * THE PACKAGE'S ONE PUBLIC SURFACE. Two tiers, in dependency order.
 *
 * `./primitives` knows nothing about bumps; `./domain` is everything that does. The two barrels stay
 * separate rather than being flattened into a hand-written list here, because the tier a component
 * belongs to is a fact worth reading off an import — and because the split is what stops a `Pill`
 * growing a `verdict` prop the next time somebody is in a hurry.
 *
 * Star re-exports are safe at THIS level and are not inside the barrels: the two tiers share no
 * exported name, so there is nothing here for ESM to drop as ambiguous.
 */

export * from './primitives'
export * from './domain'
