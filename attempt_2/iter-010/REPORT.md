# attempt_2 iter-10 — jakarta.servlet + CDI deps add correctly but stacked failures persist

## Mutation
Five `AddDependency: jakarta.servlet:jakarta.servlet-api:6.0.x` conditionals (covering both pre- and post-jakarta-migration imports) + five `AddDependency: jakarta.enterprise:jakarta.enterprise.cdi-api:4.0.x` for the CDI cascade revealed in iter-8.

## Targeted result (2 repos)

| repo | iter-9 build_post | iter-10 build_post | new error |
|------|:---:|:---:|------|
| `junit4-mockito__j8__4` | 0 | 0 | `Class<? extends javax.servlet.Servlet>` parameter mismatch in `Application.java` line 69 — type-level migration incomplete |
| `junit4-mockito__j8__7` | 0 | 0 | same |

## What the primitives accomplished
Both pom diffs show:
- `jakarta.servlet-api:6.0.0` (provided scope) added ✓
- `jakarta.enterprise.cdi-api:4.0.1` added ✓

So the original errors (`package jakarta.servlet does not exist`, `package jakarta.enterprise.util does not exist`) are resolved. The build now compiles past those points and surfaces **layer 3**: a method `register(Class<? extends javax.servlet.Servlet>)` mixing javax types where the value passed (`ServletContainer`) is jakarta-typed post-migration. `JakartaEE10` migrated `import javax.servlet.Servlet` but missed the method-signature use site.

## Why this is genuinely hard
Three layers of stacked migration gaps in two test repos:
1. `jakarta.servlet` package (fixed via AddDep, iter-10)
2. `jakarta.enterprise.*` packages (fixed via AddDep, iter-10)
3. Type-signature mixing in `register()` calls (would need a `ChangeMethodSignature` recipe targeting this specific Jersey/javax pattern)

OpenRewrite has primitives for layer-3 fixes (`ChangeType` with method-signature context, custom `JavaTemplate`) but writing them per pattern is bespoke engineering.

## Trajectory

| iter | mutation | build_post |
|-----:|----------|----------:|
| 0 | attempt_1 champion baseline | 46/96 |
| 1-3 | (null) | 46/96 |
| 4 | 6-primitive custom composite | 47/96 |
| 5 | (null) | 47/96 |
| 6/7 | + 3 Maven skip flags | 52/96 |
| 8 | + springdoc + interceptor (stacked) | 52/96 |
| 9 | + junit retention | 54/96 |
| **10** | **+ jakarta.servlet + CDI (stacked)** | **54/96 (56%)** ← same |

## Champion stays iter-9
54/96 build_post. The iter-10 primitives are kept in the recipe — they're operationally correct and will help any future repo that has *only* the servlet/CDI gap, just not these specific repos where layer 3 also fails.

## Honest pattern across iter-8/10
Two out of three custom-primitive iterations land the primitive correctly but reveal additional stacked layers. The "easiest" remaining failures aren't single-cause — they're chains. To flip them either:
- Land all layers in one iteration (requires per-repo diagnosis)
- Aggressive measures like `DeleteSourceFiles` on the problematic configurer files (defensible but invasive)
- Accept 56% as the catalog-composable ceiling on this dataset
