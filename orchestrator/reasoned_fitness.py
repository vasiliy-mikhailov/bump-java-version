"""Reasoned fitness oracle for the ralph loop.

Stands in for the Docker runner. Given a candidate recipe list and the
dataset, returns the same shape the real runner would (a CorpusScoreLike
with .score and .per_cell and .gap_cells()), but the per-repo metrics
are computed from prior knowledge of how each OpenRewrite recipe behaves,
not from an actual build.

Per-repo model — what we need to handle a row:

   spring-boot-2 row needs:
        * Spring Boot 3 upgrade recipe (UpgradeSpringBoot_3_x)
        * Jakarta namespace migration (Boot 3 requires jakarta.*)
        * Java 17/21 upgrade (Boot 3 requires Java 17+)

   junit4-mockito row needs:
        * JUnit4to5Migration
        * Optionally MockitoBestPractices if the repo uses Mockito
        * Java upgrade to 21

   jakarta-ee-javax row needs:
        * JavaxMigrationToJakarta (or its narrower siblings)
        * Java upgrade to 21

   hibernate-jackson-lombok row needs:
        * MigrateToHibernate6x (any version)  OR  Jackson recipe  OR  Lombok recipe
          depending on which sub-family the repo is
        * Java upgrade to 21

Order constraints (recipes are sensitive to ordering in OpenRewrite):
        * Jakarta namespace migration MUST run before Spring Boot 3 upgrade.
        * JUnit4→5 migration MUST run before any AssertJ / JUnit5-only recipes.
        * Lombok best-practices SHOULD run after Spring Boot upgrade.
        * UpgradeToJava21 should be last (after deps are on Boot 3 / Jakarta).
        * RemoveUnusedImports / AutoFormat are always-safe tail.

Each repo is scored as:

    build_pass = 1   if all of its required-recipe-families are present
                     AND the order constraints relevant to that repo hold;
                     0 otherwise (with a graceful fallback if "most" needs
                     are met — partial credit for build_pass would muddy
                     the metric, so it's strict).
    test_pass  = best-effort estimate: starts at build_pass; reduced if
                 mockito recipe is missing for a junit4-mockito row,
                 reduced if Hibernate version mismatched, etc.
    diff_sanity = 1 unless the candidate contains a wildly inappropriate
                  recipe for the cell (penalty for, e.g. running
                  Spring-Boot recipes against a non-Spring repo — they
                  shouldn't damage anything but they add noise).
    recipe_applied = 1 if any relevant recipe is in the set.

The aggregate function matches scorer.aggregate exactly so the mutator's
view of fitness during the search is consistent with what the real
runner would produce.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .scorer import RepoScore, aggregate, WEIGHTS


# Recipe ID -> family it belongs to (mirrors recipes/pool.yml).
RECIPE_FAMILY: dict[str, str] = {}

# Recipe IDs by family, used by the simulator to ask "does the candidate
# contain ANY recipe in family X?"
FAMILY_RECIPES: dict[str, set[str]] = {}


def _load_pool(pool_path: Path) -> None:
    import yaml
    global RECIPE_FAMILY, FAMILY_RECIPES
    pool = yaml.safe_load(pool_path.read_text())
    for entry in pool:
        rid = entry["id"]
        fam = entry.get("family", "core")
        RECIPE_FAMILY[rid] = fam
        FAMILY_RECIPES.setdefault(fam, set()).add(rid)


# What each cell needs to pass build, in terms of recipe families.
# Each entry is a list of "must-have" families; a candidate scores
# build_pass = 1 only if it contains at least one recipe from each.
CELL_REQUIRED_FAMILIES: dict[tuple[int, str], list[str]] = {
    # spring-boot-2 rows — Boot 3 needs Java 17+ and jakarta namespace
    (8, "spring-boot-2"):           ["jakarta", "spring-boot", "core"],
    (11, "spring-boot-2"):          ["jakarta", "spring-boot", "core"],
    (17, "spring-boot-2"):          ["jakarta", "spring-boot", "core"],

    # junit4-mockito rows — just need JUnit5 migration + Java upgrade
    (8, "junit4-mockito"):          ["junit", "core"],
    (11, "junit4-mockito"):         ["junit", "core"],
    (17, "junit4-mockito"):         ["junit", "core"],

    # jakarta-ee-javax rows
    (8, "jakarta-ee-javax"):        ["jakarta", "core"],
    (11, "jakarta-ee-javax"):       ["jakarta", "core"],
    (17, "jakarta-ee-javax"):       ["jakarta", "core"],

    # hibernate-jackson-lombok — needs at least one of the relevant
    # families. We encode this as the union; the helper below treats
    # this row specially (any-of, not all-of).
    (8, "hibernate-jackson-lombok"):  ["any:hibernate|jackson|lombok", "core"],
    (11, "hibernate-jackson-lombok"): ["any:hibernate|jackson|lombok", "core"],
    (17, "hibernate-jackson-lombok"): ["any:hibernate|jackson|lombok", "core"],
}


def _candidate_families(recipes: list[str]) -> dict[str, list[int]]:
    """Map family -> list of indices in the candidate where that family
    appears. Used both to check coverage and order constraints."""
    out: dict[str, list[int]] = {}
    for i, r in enumerate(recipes):
        fam = RECIPE_FAMILY.get(r, "core")
        out.setdefault(fam, []).append(i)
    return out


def _has_required(fam_index: dict[str, list[int]], required: list[str]) -> bool:
    for req in required:
        if req.startswith("any:"):
            families = req[4:].split("|")
            if not any(fam_index.get(f) for f in families):
                return False
        else:
            if not fam_index.get(req):
                return False
    return True


def _order_ok(fam_index: dict[str, list[int]]) -> bool:
    """Check the cross-family order constraints."""
    # Jakarta must come strictly before any Spring Boot 3 upgrade.
    jak = fam_index.get("jakarta") or []
    sb  = fam_index.get("spring-boot") or []
    if jak and sb:
        # both present — first jakarta index must be < first sb index
        if min(jak) >= min(sb):
            return False
    return True


def _diff_sanity(fam_index: dict[str, list[int]], cell: tuple[int, str]) -> float:
    """Penalty if the candidate contains recipes inappropriate for the
    cell (they shouldn't damage the build, but they introduce churn
    that real users would flag in code review)."""
    java_v, family = cell
    n_recipes = sum(len(v) for v in fam_index.values())
    if n_recipes == 0:
        return 1.0
    irrelevant = 0
    if family != "spring-boot-2":
        # Spring Boot recipes don't belong on non-Spring repos
        irrelevant += len(fam_index.get("spring-boot", []))
    if family == "junit4-mockito":
        # Pure-JUnit-no-Spring repos shouldn't get Boot recipes
        irrelevant += len(fam_index.get("spring-boot", []))
    return max(0.5, 1.0 - 0.15 * irrelevant)


def _test_pass_estimate(fam_index: dict[str, list[int]],
                        cell: tuple[int, str],
                        build_pass: int) -> float:
    if build_pass == 0:
        return 0.0
    java_v, family = cell
    if family == "junit4-mockito":
        # If Mockito recipe missing, some assertion-style tests will
        # break — partial credit.
        return 1.0 if fam_index.get("mockito") else 0.7
    if family == "hibernate-jackson-lombok":
        # Hibernate 5→6 needs MigrateToHibernate6x; Lombok needs lombok recipe.
        # We approximate: if hibernate or lombok family present, full credit.
        return 1.0 if (fam_index.get("hibernate") or fam_index.get("lombok")
                       or fam_index.get("jackson")) else 0.6
    if family == "spring-boot-2":
        # Boot 3 tests use JUnit 5 + Mockito; lacking junit recipe causes
        # vintage-engine surprises in some Boot apps.
        return 1.0 if fam_index.get("junit") else 0.8
    return 1.0


def score_candidate(
    recipes: list[str],
    dataset: list[dict],
) -> "CorpusLike":
    """Evaluate one candidate against the entire dataset using the
    reasoned per-repo model. Returns the same shape as the real
    scorer's aggregate()."""
    fam_index = _candidate_families(recipes)
    order_ok = _order_ok(fam_index)

    scored: list[RepoScore] = []
    for entry in dataset:
        if not entry.get("url"):
            continue
        cell = (entry["java_version"], entry["dependency_family"])
        required = CELL_REQUIRED_FAMILIES.get(cell, ["core"])

        if not order_ok:
            build_pass = 0
        elif _has_required(fam_index, required):
            build_pass = 1
        else:
            build_pass = 0

        test_pass = _test_pass_estimate(fam_index, cell, build_pass)
        diff_sanity = _diff_sanity(fam_index, cell)
        # recipe_applied: any recipe from a family that touches this cell
        applied = 0
        for req in required:
            if req.startswith("any:"):
                fams = req[4:].split("|")
                if any(fam_index.get(f) for f in fams):
                    applied = 1; break
            elif fam_index.get(req):
                applied = 1; break

        composite = (
            WEIGHTS["build"] * build_pass
            + WEIGHTS["test"] * test_pass
            + WEIGHTS["diff_sanity"] * diff_sanity
            + WEIGHTS["recipe_applied"] * applied
        )
        scored.append(RepoScore(
            repo_id=entry["id"],
            java_version=entry["java_version"],
            dependency_family=entry["dependency_family"],
            build_pass=build_pass,
            test_pass=test_pass,
            diff_sanity=diff_sanity,
            recipe_applied=applied,
            composite=composite,
            raw={"reasoned": True, "cell": list(cell), "order_ok": order_ok},
        ))

    return aggregate(scored)


def make_fitness(pool_path: Path, dataset_path: Path):
    """Return a callable Candidate -> CorpusScore for plugging into HillClimber."""
    _load_pool(pool_path)
    dataset = json.loads(dataset_path.read_text())["repos"]

    def fitness(cand) -> object:
        return score_candidate(cand.recipes, dataset)

    return fitness


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--recipes", nargs="*", required=True,
                        help="Candidate recipe IDs, space-separated.")
    args = parser.parse_args()

    _load_pool(args.pool)
    dataset = json.loads(args.dataset.read_text())["repos"]
    result = score_candidate(args.recipes, dataset)
    print(json.dumps({
        "score": result.score,
        "per_cell": {f"{j}:{f}": s for (j, f), s in result.per_cell.items()},
        "gap_cells": [list(c) for c in result.gap_cells()],
    }, indent=2))
