# Attempt 11 — the `bump_java_version` skill (baseline before automated optimization)

Attempt 11 packages the migration artifact as a portable **skill**, de-brands the recipe
catalog, fixes the build environment to run non-root, and re-measures the artifact freshly on
a de-noised corpus — the baseline before handing optimization to GEPA + EvoSkills.

## What changed since attempt 10

- **Deliverable is a skill, not a bare `prompt.md`.** P2's output is the `bump_java_version`
  skill in OpenHands AgentSkills format: `.agents/skills/bump_java_version/` = `SKILL.md`
  (the six-section migration procedure) + `scripts/` (`bump_*.sh`, `sb2_to_sb3.sh`) +
  `references/`. P3 hardens the `(skill, recipes)` artifact down the rung ladder.
- **Recipe catalog de-branded:** `com.claude.recipes:claude-recipes` →
  `tech.mikhailov.bump_java_version_recipes:bump-java-version-recipes:1.0.0` (source in
  `recipes/`, rebuilt + installed to the local `.m2` cache).
- **Docker runs non-root:** the `mvn` wrapper runs containers as the invoking uid (1000), so
  build outputs are user-owned — no more root-owned `target/` / `.m2-fitness`.
- **Corpus de-noised (two passes) → 412 datapoints.** Pass 1 (a10→a11): 477 − 46 unmigratable
  junk + 15 recovered (corrected compiling shas) = 432. Pass 2 (post-sweep sha audit): − 20 more
  junk found by reading the *actual* pom at each recorded sha — **13 already ≥ `jv_to`** (no-op
  "passes") + **7 that don't compile under `jv_from`** — leaving **412**.
- **Slugs renamed** `owner_repo__J17toJ21` → `owner_repo_<sha>`. The `__J17toJ21` label was never
  read at runtime (the skill detects `jv_from` from the pom and targets the next LTS; the verdict
  hardcodes ≥ 21), so the sha now names the exact baseline commit and fixes latent same-repo
  slug collisions.

## Skill validated across the full rung ladder

On two datapoints, all three rungs PASS — driving the skill (SKILL.md + renamed catalog +
scripts):

| Datapoint | Rung 1 (Claude+Opus) | Rung 2 (Claude+Qwen) | Rung 3 (Claude+OpenHands+Qwen) |
|---|---|---|---|
| AjinkyaGokhale/esp-flasher-java | PASS | PASS (88s) | PASS (135s) |
| LeooZeballos/fast-food | PASS | PASS (270s) | PASS (325s) |

`fast-food` also exercised the skill's "tests that error at baseline aren't your
responsibility" judgment (its `contextLoads` needs a MySQL DB absent in the sandbox) — both
LLM rungs correctly excluded it rather than failing the bump. Every rung's dialogue is
preserved under `per_repo_iter/<slug>/`.

## Corpus sweep — attempt-11 PASS rate (production rung)

Fresh, full re-run of **every** clean datapoint through **OpenHands+Qwen** with the skill
(verdict = pom ≥ `jv_to`; every datapoint re-executed from its baseline sha; dialogues preserved):

| Corpus | PASS | Rate |
|---|---|---|
| Raw (432, pre-audit) | 407 / 432 | 94.2% |
| **Cleaned (412, junk dropped)** | **394 / 412** | **95.6%** |
| + rung-1 escalation (Claude+Opus) | 397 / 412 | **96.4%** |

This is the first apples-to-apples, every-datapoint-re-executed corpus PASS rate for the skill.
A checkout audit confirmed the sweep uses the recorded baseline sha (HEAD matched in 19/19
spot-checks; the skill itself does no `git pull` / `checkout`); the junk that audit surfaced was
dropped (above).

### Rung-1 escalation — the rung-ladder thesis in action

The skill caps the weak executor (Qwen) to table-listed fixes; the strong rung (Claude+Opus) is
not so constrained. Of the 18 remaining NOT-BUMPED, rung-1 recovered **3** (all verified pom→21):

| Datapoint | Why Qwen bailed | Rung-1 fix | Result |
|---|---|---|---|
| bigboxer23/solar-moon-common | `SPOTLESS_GOOGLE_JAVA_FORMAT_JDK21_INCOMPATIBLE` — fix not in table | bump google-java-format 1.15→1.19.2 + spotless-plugin 2.38→2.43 | pom→21, compile + test-compile clean |
| rajeshcr716/Project | `SB2_BOM_NEEDS_SB3_BUMP` — needed a Spring-Security-6 recipe outside the table | full `sb2_to_sb3` + bump (no hand source edits needed) | pom→21, JDK21 compile clean |
| phani-kb/nvd-tool | `SB2_BOM_NEEDS_SB3_BUMP` | full `sb2_to_sb3` + bump | pom→21, compile clean |

The other ~15 are genuinely-hard SB2→SB3 needing manual source migration (andrmikej, entur's
residual Swagger 1.x→2.x, navikt, folio-mod-circulation-item, happycows×3) or substrate/env
issues (kismon's Vaadin `user.home`, mcollovati, Opetushallitus) — the long tail GEPA/EvoSkills
will target.

## Attempt 10, for comparison

| Metric | Rate |
|---|---|
| Raw OH+Qwen | 358 / 477 = 75.1% |
| Clean corpus (junk baselines removed) | 358 / 431 = 83.1% |
| With hardened artifact + recovery | ≈ 416 / 431 = 96.5% |

Caveat: attempt 10's 96.5% was **not** a single fresh sweep — it credited the original 358
PASS (not re-run) plus the re-checked fixable subset plus recovery. The attempt-11 sweep
re-executes *every* datapoint, so 95.6% (96.4% with rung-1) is the honest end-to-end figure.

## Next

Automate the optimization loop: **GEPA** evolves `SKILL.md` by reflecting on the preserved
dialogues, **EvoSkills** evolves the recipe catalog from failed trajectories, both scored
against `corpus_clean.json` (412). See `PLAN.md`.
