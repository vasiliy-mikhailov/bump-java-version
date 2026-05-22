# Iter-1 smoke report

3 repos, hand-picked to span the iter-0 failure modes:

- `jakarta-j17-3-CAVEAT` — spring-framework-petclinic (pre-jakarta tag) — pre=1/post=0 in iter-0; tests the "high-quality recipe but build gap" mode.
- `jakarta-j8-1` — javaee7-samples — was a "fake win" in iter-0 (compat-flag salvage); tests the "old source level" mode.
- `sb2-j11-1` — spring-petclinic-reactive — was pre=1/post=0 in iter-0; tests a true Boot 2 → 3 migration on a Java 11 reactive stack.

## Seed change vs iter-0

```diff
- org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta
+ org.openrewrite.java.migrate.jakarta.JakartaEE10
```

Rationale: iter-0 left `pom.xml` on `javax.*` artefacts even when source had been moved to `jakarta.*`. `JakartaEE10` is the broader EE-10 umbrella that also migrates pom dependency coordinates.

## What landed

| repo | base | post | rc | applied | diff | Qwen overall | Qwen highlights |
|------|------|------|----|---------|------|--------------|----------------|
| jakarta-j17-3-CAVEAT | 1 | 0 | 0 | 1 | 381 lines | **4/5** | Java 21 target, Spring 6.1, Hibernate 6.5, JakartaEE10 deps, `@Autowired` → constructor injection, `SpringJUnit4ClassRunner` → `SpringJUnitConfig`, `List.getFirst()`. Build fails on a stray `org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization` import (Boot-only class) added to a Spring-framework-only project. |
| sb2-j11-1            | 1 | 0 | 0 | 1 | 1351 lines | **4/5** | Boot 2.3 → 3.x, Spring Cloud bump, Swagger → SpringDoc, Cassandra driver, javax → jakarta, `@Serial`, lambda DSL for `SecurityWebFilterChain`. Retains a few legacy patterns (`@CrossOrigin` attrs vs global CORS, manual tracing log pattern); minor formatting inconsistencies. |
| jakarta-j8-1         | 0 | 1 | 1 | 1 | **0 lines** | **1/5** | **Fake win:** project poms pin `source/target=7` (JDK 21 javac rejects). Baseline fails, recipe fails before doing anything, but post-build "passes" because our `-Dmaven.compiler.release=21` compat flag bypasses the source 7 check. Qwen empty-diff branch correctly flags it. |

## What this iteration proves

1. **The Qwen judge is the right discriminator.** Two repos with `post=0` (build fails) score 4/5 on quality — they're doing the migration work, just blocked on specific gaps. The one repo with `post=1` (build passes) scores 1/5 — it's a metric-only win with no actual recipe contribution. Building alone is insufficient signal; Qwen separates honest progress from accidental wins.

2. **Shared `~/.m2-fitness` + Google's Maven Central mirror defeats the 429 throttling** that iter-0 hit at higher fan-out. Recipe-phase runtime dropped from 139 s cold to 42 s warm for the same repo.

3. **JakartaEE10 over JavaxMigrationToJakarta is a strict improvement** for repos that have a real Boot/Spring stack — the pom artefacts get carried along, so we don't dead-end on "source migrated, deps still on javax".

## Failure modes that survived iter-1 (and what fixes them in iter-2)

- **Boot-only recipes triggering on Spring-framework-only projects** (jakarta-j17-3-CAVEAT). `UpgradeSpringBoot_3_3` shouldn't apply on a project with no `spring-boot-starter-parent`. Either drop the Boot recipe from the seed for non-Boot cells, or rely on `JakartaEE10` + an explicit `UpgradeSpringFramework_6_1` recipe.
- **Stale source levels (`source=7`)** in `javaee7-samples`. The recipe pool needs a "force compile target to ≥ 8" step (or we drop this repo from the dataset under fitness #6's reproducibility constraint).
- **"Win" definition.** The current scorer counts `build_post == 1` as a win, which jakarta-j8-1 abuses. Tightening to `post=1 AND rc=0 AND diff_files > 0 AND qwen_overall ≥ 3` would have correctly classified jakarta-j8-1 as a non-win.

## Iter-2 changes to try (one mutation step, ralph-style)

- Replace `UpgradeSpringBoot_3_3` with the two-step `UpgradeSpringFramework_6_1` + `UpgradeSpringBoot_3_3` so framework-only projects get migrated by the first and Boot-only-class imports don't get sprayed everywhere.
- Add `org.openrewrite.java.migrate.UpgradeJavaVersion` (with `version: 21`) ahead of UpgradeToJava21 — bumps Maven `<source>`/`<target>` properties when they're below 8.
- Score with the new fitness: `quality = 0.4 * (qwen_overall / 5) + 0.4 * build_post + 0.2 * (tests_passed / tests_total)`.
