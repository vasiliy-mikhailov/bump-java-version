# Dominanta — Java 21 migration

## Purpose
This project builds and passes tests under Java 8/11/17. It must build and pass the same tests under Java 21. Self-amplify until that holds.

## Contract and constraints
Action vocabulary: `git` (init, add, commit, reset --hard, status, diff), `mvn rewrite:run -Drewrite.activeRecipes=<FQN>` (recipes only from the catalog below), `mvn compile`, `mvn test`, and the scripted fixes from the table below applied exactly as written when their `[ERROR]` trigger matches. No other edits. No source or pom changes you author yourself.

Recipe coordinates on every `mvn rewrite:run`:
```
-Drewrite.recipeArtifactCoordinates=\
org.openrewrite.recipe:rewrite-migrate-java:3.35.0,\
org.openrewrite.recipe:rewrite-spring:6.31.0,\
org.openrewrite.recipe:rewrite-testing-frameworks:3.36.0,\
org.openrewrite.recipe:rewrite-hibernate:2.20.3,\
com.claude.recipes:claude-recipes:1.0.0
```
Plugin pin: `org.openrewrite.maven:rewrite-maven-plugin:6.40.0`.

## Search hints — what observed failures have taught

- **git as checkpoint.** Before anything else, `git init && git add -A && git commit -m baseline`, then `mvn test` under jv_from to record the passing-test set (`BASELINE_PASS`). Every recipe applied later is a candidate commit; if it regresses tests vs `BASELINE_PASS`, `git reset --hard` and try the next.
- **Stepped chain beats one-shot.** Walk lombok bump → (java 8→11 if jv_from=8) → plugins17 → build17 → SB3-upgrade (if SB2 present) → plugins21 → build21 → java21 transforms. Skip steps that don't apply to this project.
- **Recipes over hand-edits.** A hand-authored fix is freelancing; it leaves no audit Qwen can defend. Reach for a recipe first; reach for a scripted fix from the table second; never invent a third option.
- **Known post-recipe build failures have scripted fixes.** The "Scripted fixes" table maps an `[ERROR]` trigger to an exact change. Apply the listed change verbatim only when the trigger matches the immediately preceding step's error output.
- **Source patterns gate custom recipes.** The "Custom claude-recipes" table maps a pattern in source to a recipe FQN. When the pattern is present, invoke that recipe after the relevant standard step (e.g. WSCA after `spring_boot_3_upgrade`).
- **A failed step is informative, not fatal.** When a step regresses or won't compile, `git reset --hard HEAD` and move to the next recipe in the chain. The chain converges over multiple steps; do not loop on one step.

## Reward
`mvn compile` under JDK 21 succeeds AND every test in `BASELINE_PASS` passes `mvn test` under JDK 21. The diff vs initial commit is the deliverable.

## Repeat
Cycle through the catalog under the discipline above until reward is approached. On exhaustion without success, state the last failing recipe + `[ERROR]` block and stop. Do not invent edits.

---

## Recipe catalog

| # | Label | JDK | Recipe FQN(s) |
|---|---|---|---|
| 1 | lombok_safe_bump | jv_from | `org.openrewrite.maven.UpgradeDependencyVersion` (`org.projectlombok:lombok` → `1.18.30`) + `org.openrewrite.maven.ChangePropertyValue` for `lombok.version`/equivalent properties |
| 2 | java8_to_java11 | 11 (when jv_from=8) | `org.openrewrite.java.migrate.Java8toJava11` |
| 3 | upgrade_plugins_for_java17 | 11 (when jv_from≤11) | `org.openrewrite.java.migrate.UpgradePluginsForJava17` |
| 4 | upgrade_build_to_java17 | 17 | `org.openrewrite.java.migrate.UpgradeBuildToJava17` |
| 5 | spring_boot_3_upgrade | 17 (when SB 2.x present) | `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3` |
| 6 | upgrade_plugins_for_java21 | 17 | `org.openrewrite.java.migrate.UpgradePluginsForJava21` |
| 7 | upgrade_build_to_java21 | 21 | `org.openrewrite.java.migrate.UpgradeBuildToJava21` |
| 8 | java21_transforms | 21 | `RemoveIllegalSemicolons`, `ThreadStopUnsupported`, `URLConstructorToURICreate`, `SequencedCollection`, `UseLocaleOf`, `ReplaceDeprecatedRuntimeExecMethods`, `DeleteDeprecatedFinalize`, `RemovedSubjectMethods` (under `org.openrewrite.java.migrate.*` / `org.openrewrite.staticanalysis.*`) |

## Custom claude-recipes (invoke as recipes when source pattern matches)

| Source pattern | Recipe FQN |
|---|---|
| `extends WebSecurityConfigurerAdapter` | `com.claude.recipes.RewriteWebSecurityConfigurerAdapterToFilterChain` |
| `@WebMvcTest(secure = false)` | `com.claude.recipes.AddSecurityConfigImportForWebMvcTest` |
| Global `oauth2Login.authenticationEntryPoint` | `com.claude.recipes.ScopeAuthenticationEntryPointToApiForOAuth2Login` |
| `HttpStatus` returned where Spring 6 expects `HttpStatusCode` | `com.claude.recipes.WidenHttpStatusToHttpStatusCode` |

## Scripted fixes (apply exactly, only on matching `[ERROR]`)

| `[ERROR]` trigger | Exact fix |
|---|---|
| `Could not find artifact org.liquibase.ext:liquibase-hibernate5` | In pom.xml: replace every `<artifactId>liquibase-hibernate5</artifactId>` with `<artifactId>liquibase-hibernate6</artifactId>`; set `<liquibase.version>4.27.0</liquibase.version>`. |
| `Could not resolve [...] htmlunit:jar:2.6` | In pom.xml: set `net.sourceforge.htmlunit:htmlunit` version to `2.70.0`. |
| `Could not find artifact org.springdoc:springdoc-openapi-ui` | In pom.xml: replace `<artifactId>springdoc-openapi-ui</artifactId>` with `<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>` and set version `2.3.0`. |
