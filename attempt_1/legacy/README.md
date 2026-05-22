# Java-21 OpenRewrite Fitness Loop

A headless ralph loop that searches for the best composition of existing OpenRewrite recipes to migrate the dataset corpus (Java 8/11/17 + Spring Boot 2 / JUnit 4 / Jakarta / Hibernate-Jackson-Lombok) to Java 21. Each iteration spins one Docker container per repo, applies a candidate `rewrite.yml`, attempts to build and test the result, and scores it. A hill-climbing mutator proposes the next candidate.

## What the metric measures

Per repo, score in `[0, 1]` is:

```
score = 0.5 * build_pass
      + 0.3 * test_pass
      + 0.1 * diff_sanity
      + 0.1 * recipe_applied
```

The build is the loudest signal so it carries the most weight; test pass-rate refines among recipes that build; `diff_sanity` punishes recipes that touch binary artefacts; `recipe_applied` protects against the degenerate empty-composition that "doesn't break anything" because it does nothing.

Corpus aggregation is a mean of cell-means, so cells that happen to have more samples (e.g. Java 8 + Jakarta) don't outweigh cells that are thin (e.g. Java 17 + Boot 2).

The full justification and tradeoffs are in `orchestrator/scorer.py`.

## What the loop searches over

Each candidate is an ordered list of OpenRewrite recipe IDs drawn from `recipes/pool.yml`. The mutator applies one of four operators per proposal:

- `ADD` a recipe (biased toward families where cells are currently weak)
- `REMOVE` a recipe (biased toward saturated cells where extra recipes only churn)
- `SWAP` two adjacent recipes (order matters — Jakarta before Spring Boot 3, for example)
- `REPLACE` a recipe with a same-family sibling

The search stops when 3 consecutive iterations fail to improve the corpus score by more than 2%, capped at 20 iterations. Defaults are CLI-overridable.

## Layout

```
harness/
├── Dockerfile                  multi-JDK (8/11/17/21) runner image
├── Makefile                    image | smoke | dry-run | loop | best | clean
├── recipes/
│   ├── pool.yml                full catalogue the mutator draws from
│   └── seed.yml                conservative starting composition
├── scripts/
│   └── run_one_repo.sh         container entrypoint: clone -> recipe -> build -> test -> metrics
├── orchestrator/
│   ├── orchestrator.py         end-to-end loop driver
│   ├── mutator.py              hill climber + mutation ops
│   ├── scorer.py               per-repo and corpus scoring
│   └── repo_runner.py          docker fan-out
├── tests/                      pure-Python smoke tests
└── results/                    iteration outputs (created at runtime)
```

## How to run

Requirements on the host:
- Docker (with enough disk; full corpus uses ~20-40 GB across iterations)
- Python 3.10+ with `pyyaml`
- `jq` for the `make best` target

```sh
# 1. Sanity-check the Python pieces with no Docker
make smoke

# 2. Dry-run the loop against a synthetic fitness function — fast, validates the mutator
make dry-run

# 3. Build the runner image (one time, ~3-5 GB pulled)
make image

# 4. Full loop against the dataset
make loop PARALLEL=6 MAX_ITER=20

# 5. Show the winning recipe
make best
```

`PARALLEL` is how many containers run concurrently. Sensible values are 2-8 depending on host RAM (each container peaks ~3 GB during the post-recipe Java 21 build).

## What you get per iteration

```
results/iter-001/
├── _recipe/rewrite.yml                     the candidate evaluated this iter
├── sb2-j8-1/metrics.json + run.log         per-repo
├── sb2-j8-2/metrics.json + run.log
├── ...
└── corpus_score.json                       aggregated score for the iter
```

And once the loop finishes:

```
results/best.json    { "best": [...recipes...], "best_score": 0.78, "history": [...] }
```

## Caveats

- The runner clones each repo on every iteration. For corpus-wide iteration runtimes <30 min, mount a host Git cache (`-v $HOME/.git-cache:/root/.git-cache`) and switch the script to `git clone --reference`.
- OpenRewrite plugin versions are pinned in `scripts/run_one_repo.sh`. Bump them in lockstep when refreshing the recipe pool.
- Repos flagged `*_CAVEAT` in the dataset declare a lower `compiler.release` than they actually need. The runner forces `-Dmaven.compiler.release=21` post-recipe so the override works automatically. Repos flagged `*_GAP` are skipped (they have no URL).
- The Gradle path is functional but less tested than the Maven path; if you see flakes there, run Maven-only via `--parallel 1` and let me know which repo fails.

## Wiring an LLM mutator later

The mutator is structured so that swapping `HillClimber` for an LLM-driven one is a small change: re-implement `_propose` to ask an LLM "given this candidate (score X) and the per-cell breakdown, propose 5 neighbours" and parse the response. The fitness contract (`Candidate -> CorpusScoreLike`) stays the same.
