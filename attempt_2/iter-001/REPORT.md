# attempt_2 iter-1 — null result: SpringFoxToSpringDoc didn't help

## Mutation
Inserted `org.openrewrite.java.springdoc.SpringFoxToSpringDoc` after `UpgradeJavaVersion(21)` and before `JakartaEE10`, hypothesizing it would fix springfox.documentation failures.

## Wrinkle caught en route
First attempt used the wrong coordinate (`org.openrewrite.java.spring.framework.SpringfoxToSpringDocOpenApi`) — recipe failed to initialize on all 96, producing empty diffs, but build_post jumped to 76/95 because the recipe-failed path falls through to baseline build (all dataset entries pass baseline). The Qwen judge caught this fake win cleanly: empty_diff=89 → overall=1.0 on 89/96. Reset, fixed coordinate, re-ran.

## Result (after coordinate fix)

| metric | iter-0 | iter-1 | delta |
|--------|------:|------:|------:|
| mean Qwen overall | 3.15 | 3.11 | -0.04 |
| build_post pass | 46/96 | 46/96 | 0 |
| empty diffs | 9 | 9 | 0 |

Per-cell changes within ±0.12 — noise. No cell moved in build_post.

## Why it didn't help
Springfox failures require pom-level dependency replacement (springfox-swagger2 → springdoc-openapi-starter-webmvc-ui), but `SpringFoxToSpringDoc` transforms *imports and code*, not Maven coordinates. The pom still references springfox jars that aren't on classpath after the Boot upgrade.

## Next mutation candidates (not run)
1. `org.openrewrite.java.springdoc.ReplaceSpringFoxDependencies` — pom-coordinate side
2. `org.openrewrite.java.spring.boot3.SpringBoot3BestPractices` — cleanup
3. `UpgradeSpringBoot_3_5` instead of `_3_3` — more aggressive Boot
4. `MigrateToHibernate66` vs `MigrateToHibernate62` for 11/hibernate-5 cell

## Trajectory
- iter-0 baseline: Q 3.15, 46/96 build_post
- iter-1: Q 3.11, 46/96 build_post — **reject mutation, revert to iter-0 recipe**

Champion remains the attempt_1 iter-2 recipe.
