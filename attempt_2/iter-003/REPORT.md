# attempt_2 iter-3 — null result: MigrateToHibernate63 swap didn't help

## Mutation
Swapped `MigrateToHibernate62` → `MigrateToHibernate63` in the champion composition. (Initially attempted `MigrateToHibernate66`, but it's not in our `rewrite-hibernate:2.9.0` jar — the published catalog goes up to 6.6 but the version in our cache stops at 6.3; jar inspection confirmed `META-INF/rewrite/hibernate-6.3.yml` is the newest available.)

## Result

| metric | iter-0 | iter-3 | delta |
|--------|------:|------:|------:|
| mean Qwen overall | 3.15 | 3.14 | -0.01 |
| build_post pass | 46/96 | 46/96 | 0 |
| empty diffs | 9 | 9 | 0 |

Per-cell deltas vs iter-0 all within ±0.25 — noise. **Zero build_post movement on any cell**, including the targeted `11/hibernate-5` (3/8 → 3/8) and `8/hibernate-5` (1/8 → 1/8).

## Why it didn't help
Inspecting `8/hibernate-5__j8__1` failure pre and post:
- iter-0 (Hibernate62): `package org.hibernate.annotations does not exist`
- iter-3 (Hibernate63): same error, same line

Both recipes target source-level migrations of Hibernate API references but neither bumps the `hibernate-core` artifact version in pom.xml when it's declared directly (rather than inherited from Spring Boot BOM). So the same compile error reproduces.

## Trajectory (iter-0..3)

| iter | mutation | mean Q | build_post | verdict |
|-----:|----------|------:|----------:|---|
| 0 | attempt_1 champion baseline | 3.15 | 46/96 (48%) | — |
| 1 | + SpringFoxToSpringDoc | 3.11 | 46/96 | **null, reject** |
| 2 | + ReplaceSpringFoxDependencies + SpringFoxToSpringDoc | 3.12 | 46/96 | **null, reject** |
| 3 | swap MigrateToHibernate62 → 63 | 3.14 | 46/96 | **null, reject** |

**Three sequential mutations, zero build_post movement.** The 46/96 wall is real and structurally bounded.

## What single-recipe mutations can't fix
The 50 failing repos have failure modes that require **pom-level artifact replacement**, not just import/code rewrites:
- `hibernate-core` 5.x → 6.x as a *direct* dep (not Boot BOM)
- `springfox-*` → `springdoc-openapi-*` (pom coordinates, not just code)
- `spring-boot-maven-plugin` 2.x → 3.x (plugin version stays)
- Custom recipe to remove `WebSecurityConfigurerAdapter` properly (OR currently leaves a manual TODO)
- Custom recipe to swap `org.thymeleaf.spring4` → `org.thymeleaf.spring6`

These are *real engineering* (custom OpenRewrite recipes or AddDependency/ChangePluginVersion compositions), not single-line YAML inserts of existing recipes.

## Plateau declared
Champion remains attempt_1 iter-2 (= attempt_2 iter-0). Three iterations of cheap single-recipe mutations all bounced off the 46/96 ceiling. Future work would need to invest in custom recipe composition (build_post=0 root-cause-driven), not just catalog-recipe insertions.
