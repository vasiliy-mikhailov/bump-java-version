# Java ↔ Framework Compatibility Matrix

Use this as the authoritative reference when classifying diff hunks as REQUIRED vs
ORTHOGONAL for a `jv_from → jv_to` migration. A change is REQUIRED if and only if
applying jv_to without it would break the build, per these tables.

## Spring Boot

| Spring Boot | Java versions supported | Notes |
|---|---|---|
| 1.x          | 6, 7, 8           | EOL long ago. javax.* |
| 2.0          | 8                 | javax.* |
| 2.1          | 8, 11             | javax.* |
| 2.2          | 8, 11, 12, 13     | javax.* |
| 2.3          | 8, 11, 14         | javax.* |
| 2.4          | 8, 11, 15         | javax.* |
| 2.5          | 8, 11, 16         | javax.* |
| 2.6          | 8, 11, 17         | javax.* |
| 2.7 (final 2.x, EOL Nov 2023) | 8, 11, 17, 18 | javax.* |
| 3.0          | 17+ required      | **jakarta.* namespace** |
| 3.1          | 17, 20            | jakarta.* |
| 3.2          | 17, 21            | jakarta.* |
| 3.3          | 17, 21, 22        | jakarta.* |
| 3.4          | 17, 21, 22, 23    | jakarta.* |

**Rule of thumb:** for jv_to=17, minimum compatible SB is 2.6 (prefer 2.7 to stay on
javax.* and avoid the jakarta migration). For jv_to=21, minimum is SB 3.2.

## OpenRewrite primitives for SB version bumps

```
org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_{0..7}
org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_{0..4}
```

Each is transitive (chains the prior versions). Best practice from our empirical
trajectory data: walk SB up under jv_from to the highest compatible, then bump Java,
then optionally continue under jv_to. Jumping straight to SB 3.3 from SB ≤ 2.5 has
demonstrably high regression risk.

## Other compatibility notes

- **JaCoCo**: 0.8.8+ supports JDK 17. 0.8.10+ supports JDK 21.
- **Maven Compiler Plugin**: 3.10+ supports JDK 17; 3.11+ for JDK 21.
- **Lombok**: 1.18.30+ for JDK 21.
- **JAXB**: javax.xml.bind dropped in JDK 11; needs jakarta.xml.bind:jakarta.xml.bind-api + jakarta.xml.bind:jaxb-runtime added explicitly.

## What's NOT required for a JDK migration

- New feature code (new modules, new endpoints, new domain classes)
- IDE/editor configs (.vscode, .idea, .editorconfig)
- README, docs, examples
- Lockfile updates (package-lock.json, yarn.lock)
- GitHub Actions / CI YAML updates (unless they pin a JDK image)
- Test framework version bumps (unless old version doesn't support jv_to)
- Optional dependency security/feature updates
- Code formatting, import reordering, refactors that preserve semantics
- Renaming/restructuring that's orthogonal to the JDK change

## Decision rule — DO NOT add an SB upgrade step unless the matrix says you must

Before adding any `UpgradeSpringBoot_*` step (e.g. `org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3`) to the chain, run this exact check:

1. Read the project's current SB version from `stage_facts.spring_boot_version`.
2. If that field is absent or null, the project is not a Spring Boot project. **Do not add any SB upgrade step.**
3. Find the row for the current SB major.minor in the Spring Boot table above (e.g. "3.2" matches the "3.2" row).
4. Check the "Java versions supported" column on that row.
5. If `jv_to` is in that list, the current SB already supports the target JDK. **Do not add an SB upgrade step.** The recipe chain only needs JDK-bump primitives.
6. Only if `jv_to` is NOT in that list, add the smallest SB bump that gets the project to a row containing `jv_to`. Prefer:
   - Within 2.x: `UpgradeSpringBoot_2_7`
   - 2.x → 3.x: `UpgradeSpringBoot_3_3` (encompasses 2.x→3.0 jakarta migration)
   - Already-3.x → newer 3.y: `UpgradeSpringBoot_3_{y}` matching the smallest row that includes jv_to.

### Worked examples

| Current SB | jv_to | Decision |
|---|---|---|
| 3.2.5 | 21 | No SB upgrade — row "3.2" supports 17, 21 |
| 2.7.18 | 21 | Add `UpgradeSpringBoot_3_3` — row "2.7" supports only 8/11/17/18, not 21 |
| 2.3.12 | 17 | Add `UpgradeSpringBoot_2_7` or `UpgradeSpringBoot_3_3` — row "2.3" doesn't list 17 |
| 3.4.1 | 21 | No SB upgrade — row "3.4" supports 17, 21, 22, 23 |
| (no Spring Boot) | 21 | No SB upgrade — project is not Spring Boot |

If you add an SB upgrade step that violates this rule, the resulting chain will typically regress projects that were already compatible. Several stages have been observed to fail this way: a default `lombok → java17_transforms → java21_build` chain PASSes them, but injecting an unnecessary `UpgradeSpringBoot_3_3` causes compile failure mid-chain.
