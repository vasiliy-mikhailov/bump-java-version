# attempt_9 — Qwen-as-proposer + library + claude-recipes

## Thesis

Baseline: attempt_8 final state = **162/202 PASS (80.2%)** via Qwen-as-proposer over the
202-stage corpus, *without* today's WSCA recipe or 4 new library entries.

attempt_9 keeps the exact attempt_8 pipeline (Qwen-as-proposer for *every* iteration,
including iter-0) but layers on:
- `com.claude.recipes.RewriteWebSecurityConfigurerAdapterToFilterChain` (AST-aware WSCA → SecurityFilterChain rewrite)
- New observation-library entries: `wsca_removed_in_ss6`, `sb3_security_autoconfig_moved`,
  `thymeleaf_extras_springsecurity5_in_sb3`, `webmvctest_secure_attribute_removed_sb3`
- The `fold_into:sb3` placement primitive

**No hardcoded default chains.** Qwen authors iter-0 from scratch using
SYSTEM_PROPOSER + stage_facts. Library matches inject on every retry.

For iter 1 (post-iter-0 analysis): Claude-as-harness picks each remaining FAIL
and either (a) extends a claude-recipe or (b) adds a library entry, then teaches
Qwen by updating the library.

## Target

Beat attempt_8's 80.2% by **≥5 percentage points** → **≥85% PASS** on the same 202 stages.

## Layout

```
attempt_9/
  per_repo_iter/<slug>/trajectory.json    # one per stage
  logs/                                   # parallel worker stdout/stderr
  README.md                               # this file
```

## Driver

Run via `ITER_OUT_DIR=attempt_9/per_repo_iter` env var.
Parallel sweep: `sweep_qwen_parallel.sh` launches N workers, each calling
`iterate_one` on one stage at a time.
