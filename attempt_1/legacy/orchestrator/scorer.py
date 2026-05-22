"""Scoring for the Java-21 OpenRewrite fitness loop.

Per-repo composite score (in [0, 1]):
    score = 0.5 * build_pass
          + 0.3 * test_pass
          + 0.1 * diff_sanity
          + 0.1 * recipe_applied

  build_pass     - 1 if the project assembles cleanly on Java 21 after the
                   recipe, 0 otherwise. Hardest signal and weighted most.
  test_pass      - fraction of tests passing on Java 21. 0 if the build
                   never reached the test phase. Capped at 1.
  diff_sanity    - 1 minus the share of touched files that are binary /
                   build outputs that OpenRewrite should never modify.
                   Read as "the diff looks reasonable".
  recipe_applied - 1 if at least one file changed, else 0. Protects
                   against the trivial degenerate solution where the
                   composite recipe is empty -> nothing breaks but
                   nothing is migrated either.

Corpus aggregation:
    Each repo is assigned to a (java_version, dependency_family) cell.
    Within a cell, scores are averaged. Across cells, the corpus score
    is the mean of cell scores so that over-represented cells (Jakarta
    Java 8) don't dominate over thin cells (Boot 2 Java 17).

The scorer is pure — given a list of per-repo metric JSON files, it
returns a deterministic score and a structured breakdown that the
mutator can read to decide what to change next.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


WEIGHTS = {
    "build": 0.5,
    "test": 0.3,
    "diff_sanity": 0.1,
    "recipe_applied": 0.1,
}


@dataclass
class RepoScore:
    repo_id: str
    java_version: int
    dependency_family: str
    build_pass: float
    test_pass: float
    diff_sanity: float
    recipe_applied: float
    composite: float
    raw: dict = field(repr=False)

    @property
    def cell(self) -> tuple[int, str]:
        return (self.java_version, self.dependency_family)


def score_one(metrics: dict, dataset_entry: dict) -> RepoScore:
    """Convert one per-repo metrics.json + its dataset row to a RepoScore."""
    build_pass = 1.0 if metrics.get("build_post") == 1 else 0.0

    total = metrics.get("tests_total_post", 0) or 0
    passed = metrics.get("tests_passed_post", 0) or 0
    if total > 0:
        test_pass = max(0.0, min(1.0, passed / total))
    else:
        # No tests ran (either repo had none or the build never reached
        # the test phase). Don't punish a repo that genuinely has no
        # tests — but if the build failed, build_pass already carries
        # that signal.
        test_pass = 1.0 if build_pass == 1.0 else 0.0

    diff_files = metrics.get("diff_files", 0) or 0
    diff_binary = metrics.get("diff_binary_files", 0) or 0
    if diff_files == 0:
        diff_sanity = 1.0
    else:
        diff_sanity = max(0.0, 1.0 - (diff_binary / diff_files))

    recipe_applied = 1.0 if metrics.get("recipe_applied") == 1 else 0.0

    composite = (
        WEIGHTS["build"] * build_pass
        + WEIGHTS["test"] * test_pass
        + WEIGHTS["diff_sanity"] * diff_sanity
        + WEIGHTS["recipe_applied"] * recipe_applied
    )

    return RepoScore(
        repo_id=dataset_entry["id"],
        java_version=dataset_entry["java_version"],
        dependency_family=dataset_entry["dependency_family"],
        build_pass=build_pass,
        test_pass=test_pass,
        diff_sanity=diff_sanity,
        recipe_applied=recipe_applied,
        composite=composite,
        raw=metrics,
    )


@dataclass
class CorpusScore:
    score: float                       # overall fitness in [0, 1]
    per_cell: dict[tuple[int, str], float]
    per_repo: list[RepoScore]
    n_evaluated: int
    n_skipped: int

    def gap_cells(self, threshold: float = 0.6) -> list[tuple[int, str]]:
        """Cells whose mean score is below `threshold` — the mutator
        should bias its next proposals toward fixing these."""
        return [cell for cell, s in self.per_cell.items() if s < threshold]


def aggregate(per_repo: Iterable[RepoScore], skipped: int = 0) -> CorpusScore:
    repos = list(per_repo)
    if not repos:
        return CorpusScore(
            score=0.0, per_cell={}, per_repo=[], n_evaluated=0, n_skipped=skipped
        )

    by_cell: dict[tuple[int, str], list[float]] = {}
    for r in repos:
        by_cell.setdefault(r.cell, []).append(r.composite)

    per_cell = {cell: sum(scores) / len(scores) for cell, scores in by_cell.items()}
    # Mean of cell-means so density doesn't bias the overall score.
    score = sum(per_cell.values()) / len(per_cell) if per_cell else 0.0

    return CorpusScore(
        score=score,
        per_cell=per_cell,
        per_repo=repos,
        n_evaluated=len(repos),
        n_skipped=skipped,
    )


def load_and_score(results_dir: Path, dataset: list[dict]) -> CorpusScore:
    """Walk the orchestrator's per-iteration results directory, load each
    repo's metrics.json, and aggregate."""
    by_id = {entry["id"]: entry for entry in dataset if entry.get("url")}

    scored: list[RepoScore] = []
    skipped = 0
    for metrics_path in sorted(results_dir.glob("*/metrics.json")):
        repo_id = metrics_path.parent.name
        entry = by_id.get(repo_id)
        if not entry:
            skipped += 1
            continue
        with metrics_path.open() as f:
            metrics = json.load(f)
        scored.append(score_one(metrics, entry))

    return aggregate(scored, skipped=skipped)


def pretty(corpus: CorpusScore) -> str:
    lines = [
        f"corpus score: {corpus.score:.4f}  (n={corpus.n_evaluated}, skipped={corpus.n_skipped})",
        "per cell:",
    ]
    for cell, s in sorted(corpus.per_cell.items()):
        java, family = cell
        lines.append(f"  java{java:>2} / {family:<28}  {s:.4f}")
    gaps = corpus.gap_cells()
    if gaps:
        lines.append(f"gap cells (<0.60): {gaps}")
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    args = parser.parse_args()

    dataset = json.loads(args.dataset.read_text())["repos"]
    corpus = load_and_score(args.results, dataset)
    print(pretty(corpus))
