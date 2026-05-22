# attempt_2 — dataset rediscovery

## Outcome

96 baseline-buildable, distinct-owner-per-cell repos across the full 3x4 matrix.

| Java | Family | Entries |
|-----:|--------|--:|
| 8  | spring-boot-2     | 8 |
| 8  | jakarta-ee-javax  | 8 |
| 8  | junit4-mockito    | 8 |
| 8  | hibernate-5       | 8 |
| 11 | spring-boot-2     | 8 |
| 11 | jakarta-ee-javax  | 8 |
| 11 | junit4-mockito    | 8 |
| 11 | hibernate-5       | 8 |
| 17 | spring-boot-2     | 8 |
| 17 | jakarta-ee-javax  | 8 |
| 17 | junit4-mockito    | 8 |
| 17 | hibernate-5       | 8 |

Each entry is clone-and-checkout reproducible from commit_sha alone and baseline-buildable
(mvn compile or gradle compileJava) on its declared Java version inside the runner Docker.

## Methodology

### Family selection
Anchored to OpenRewrite catalog modules that target Java 21 breaking changes:
- spring-boot-2     -> rewrite-spring/UpgradeSpringBoot_3_x
- jakarta-ee-javax  -> rewrite-migrate-java/JakartaEE10
- junit4-mockito    -> rewrite-testing-frameworks/JUnit4to5Migration + MockitoBestPractices
- hibernate-5       -> rewrite-hibernate/MigrateToHibernate62

### Search rounds
- Round 1: 9 GitHub repo/search queries x 3 pages each, JVM language + size 50-6000 KB -> 1777 unique candidates.
- Round 2: 9 GitHub code/search queries with explicit <java.version>11/17 + family signatures -> 1708 new candidates (union 3485).

### Classification
Fetched root pom.xml / build.gradle / build.gradle.kts via gh api and extracted declared Java
version (handling maven.compiler.release, property cross-refs, Gradle styles) + family signatures
+ module count. 1823 of 3485 had a parseable root build file.

### Pivot: history walk
Direct classification only found 1-3 distinct owners per Java 11/17 cell because the world
has migrated past these intersections. Pivoted to walk git history: shallow-clone each
candidate with --filter=blob:none, then enumerate git log on pom.xml and check each historical
commit for <java.version>11/17 AND family signature. 509 (repo, version) hits found.

### Baseline build verification
For each cell, took up to 20 distinct-owner candidates sorted smaller-first (module count, size_kb),
cloned at commit_sha, ran mvn -DskipTests compile (or Gradle equivalent) inside j21-fitness:latest
Docker with the matching JDK from /opt/jdk/{8,11,17}. Per cell: built until 8 distinct-owner repos passed.

## Files

- java21-migration-dataset.json    -- final dataset (96 entries)
- FAMILIES.md                      -- family definitions
- discover/raw/                    -- raw GitHub search responses
- discover/candidates.json         -- 1777 round-1 candidates
- verify/classified_v2.json        -- 1823 classified candidates
- verify/history_hits.json         -- 509 (repo, java_version) hits from git-history walk
- verify/baseline/<fam>__j<v>__<safe>/{metrics.json,run.log}  -- per-attempt outcomes
- verify/baseline_summary.json     -- 96 pass entries

## Pool sizes

| Java | Family | Pool size |
|-----:|--------|--:|
| 8  | spring-boot-2     | 185 |
| 8  | jakarta-ee-javax  | 79 |
| 8  | junit4-mockito    | 95 |
| 8  | hibernate-5       | 42 |
| 11 | spring-boot-2     | 212 |
| 11 | jakarta-ee-javax  | 155 |
| 11 | junit4-mockito    | 29 |
| 11 | hibernate-5       | 46 |
| 17 | spring-boot-2     | 79 |
| 17 | jakarta-ee-javax  | 100 |
| 17 | junit4-mockito    | 20 |
| 17 | hibernate-5       | 25 |

Even the thinnest cell (17/junit4-mockito) had 20 candidates - comfortably above 8.

## Key finding

Direct repo-state classification severely under-counted Java 11/17 cells because few repos
currently have both Java 11/17 AND a legacy dep family. The world migrated. But many Java 21
repos today were once on Java 11 or 17 with those dep families - and git log on pom.xml
recovers those commits cleanly. This made the 8-per-cell target reachable for every cell,
including Java 17 + Hibernate 5 which we initially declared structurally impossible.
