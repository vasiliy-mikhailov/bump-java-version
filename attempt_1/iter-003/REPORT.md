# Iter-3 report — REGRESSION, rolling back next iter

## Mutations on top of iter-2

Three additions, dropping `jakarta-j8-1` from smoke (fitness #6 violation):

- **ADD** `org.openrewrite.java.migrate.lombok.LombokBestPractices` (intent: fix `hjl-j8-3`'s Lombok-on-JDK-21 missing-symbol issue)
- **ADD** `org.openrewrite.java.springdoc.SpringFoxToSpringDoc` (intent: complete `sb2-j11-1`'s Springfox→SpringDoc migration that `UpgradeSpringBoot_3_3` left incomplete)
- Smoke pruned to 4 repos: `hjl-j17-2`, `hjl-j8-3`, `jakarta-j17-3-CAVEAT`, `sb2-j11-1`

Also fixed two harness defects discovered during the run:
- First launch used wrong ID `org.openrewrite.java.spring.boot3.MigrateSpringFoxToSpringDoc` (doesn't exist). Corrected to `org.openrewrite.java.springdoc.SpringFoxToSpringDoc` by reading the recipe JAR's bundled YAML.
- Added `-Dspring-javaformat.skip=true -Dformat.skip=true` to the compat flags after the Spring petclinic build failed on a format-validator plugin.

## Outcomes (4 repos)

| repo | iter-2 | iter-3 | Δ |
|------|--------|--------|---|
| `hjl-j17-2` | post=1, diff=37, Qwen=4 | **post=0**, diff=360, Qwen=3 | **regressed** (LombokBestPractices broke `PetType`/`Pet`/`PetValidator` — added `@Getter` but rest of code can't see the generated methods; build fails on `cannot find symbol getName()/getType()`) |
| `hjl-j8-3` | post=0, diff=6789, Qwen=4 | post=0, diff=7068, Qwen=4 | unchanged (slightly bigger diff, same outcome) |
| `jakarta-j17-3-CAVEAT` | post=0, diff=381, Qwen=4 | post=0, diff=794, Qwen=3 | quality dropped (Lombok added to a non-Lombok project, Qwen marks it as un-idiomatic) |
| `sb2-j11-1` | post=0, diff=1351, Qwen=4 | post=0, diff=1491, Qwen=4 | small diff growth from SpringFoxToSpringDoc, build still fails |

**Aggregates:**
- Mean Qwen.overall: **3.5** (down from 4.0 in iter-2)
- `build_post` pass rate: **0/4** (down from 1/4 in iter-2)
- Net: regression on both metrics

## Diagnosis

1. **`LombokBestPractices` is universally toxic in this seed.** It's a *code-style* recipe that promotes Lombok annotations — but on projects that DON'T already use Lombok (petclinic) it adds `@Getter`/`@Setter` while removing or leaving stale the manual getters/setters, breaking the build. Even where it works, Qwen judges it as un-idiomatic for Java 21 (which has records).
2. **`SpringFoxToSpringDoc` is the right idea but insufficient.** It added 140 more diff lines to `sb2-j11-1` but the build still fails — the migration isn't complete enough for Spring Boot 3's webflux + reactive code.
3. **`jakarta-j17-3-CAVEAT` is unfixable from the seed.** The build failure is `cannot find DependsOnDatabaseInitialization`, an import that `UpgradeSpringBoot_3_3` inappropriately added to a Spring-framework-only project. No recipe in the catalogue removes that import. The right fix is **don't run Boot recipes on non-Boot projects** — requires cell-aware composition.

## Iter-4 plan

Ralph-rollback: revert LombokBestPractices, keep SpringFoxToSpringDoc (it's not actively hurting), accept jakarta-j17-3-CAVEAT can't be fixed by recipe alone.

```diff
- - org.openrewrite.java.migrate.lombok.LombokBestPractices
  - org.openrewrite.java.springdoc.SpringFoxToSpringDoc
```

Larger architectural mutation (deferred to iter-5+): make the recipe set **cell-aware** — Boot recipes only when the repo's pom declares spring-boot-starter-parent. Either by per-repo `rewrite.yml` selection, or by `Precondition` recipes that gate on a detected pom property.
