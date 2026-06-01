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
- **Corpus de-noised:** `corpus_clean.json` = 432 datapoints (477 minus 46 unmigratable junk
  baselines D4 wrongly collected; includes 15 recovered baselines with corrected compiling shas).

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
preserved under `per_repo_iter/<slug>/` (`dialogue.claude_opus.log`,
`dialogue.qwen.messages.json`, `dialogue.oh_qwen.log`).

## Corpus sweep — attempt-11 PASS rate (production rung)

Fresh, full re-run of **all 432** clean datapoints through **OpenHands+Qwen** with the skill
(verdict = pom ≥ jv_to, dialogues preserved per datapoint):

> **In progress — 142 / 432 so far: 128 PASS · 14 not-bumped · 0 clone-fail → ~90% PASS.**
> (Final pending; this section is updated on completion.)

This is the first apples-to-apples, every-datapoint-re-executed corpus PASS rate for the skill.
The ~14 not-bumped so far are the genuinely-harder tail (agent reaches the bump but pom doesn't
hit `jv_to` — SB2/preview/turn-limit cases) — the long tail GEPA/EvoSkills will target.

## Attempt 10, for comparison

| Metric | Rate |
|---|---|
| Raw OH+Qwen | 358 / 477 = 75.1% |
| Clean corpus (junk baselines removed) | 358 / 431 = 83.1% |
| With hardened artifact + recovery | ≈ 416 / 431 = 96.5% |

Caveat: attempt 10's 96.5% was **not** a single fresh sweep — it credited the original 358
PASS (not re-run) plus the re-checked fixable subset plus recovery. The attempt-11 sweep above
re-executes *every* datapoint, so it is the honest end-to-end figure for the skill.

## Next

Automate the optimization loop: **GEPA** evolves `SKILL.md` by reflecting on the preserved
dialogues, **EvoSkills** evolves the recipe catalog from failed trajectories, both scored
against `corpus_clean.json`. See `PLAN.md`.
