"""Hill-climbing mutator over OpenRewrite recipe-set compositions.

Genome:    an ordered list of recipe IDs (the `active_recipes` field of
           a rewrite.yml). Duplicates not allowed.

Fitness:   provided by the orchestrator — a CorpusScore.score in [0, 1].

Neighbour proposals:
    ADD     — insert a recipe from the pool, biased toward the family of
              gap cells if any (see CorpusScore.gap_cells).
    REMOVE  — drop a recipe whose family is already saturated.
    SWAP    — pick two adjacent recipes and swap them (order matters in
              some OpenRewrite compositions).
    REPLACE — swap one recipe for another in the same family.

Each iteration: propose K neighbours, evaluate, keep the best if it
strictly beats the current incumbent. Stop after `max_iter` or when
3 consecutive iterations produced no improvement >= `plateau_eps`.
"""

from __future__ import annotations

import copy
import dataclasses
import random
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


@dataclass
class RecipeRef:
    id: str
    family: str
    notes: str | None = None

    @classmethod
    def from_pool_entry(cls, entry: dict) -> "RecipeRef":
        return cls(id=entry["id"], family=entry.get("family", "core"),
                   notes=entry.get("notes"))


@dataclass
class Candidate:
    recipes: list[str]          # ordered list of recipe IDs

    def __hash__(self) -> int:
        return hash(tuple(self.recipes))

    def clone(self) -> "Candidate":
        return Candidate(recipes=list(self.recipes))


@dataclass
class MutatorConfig:
    proposals_per_iter: int = 5
    plateau_eps: float = 0.02
    plateau_window: int = 3
    max_iter: int = 20
    max_recipes: int = 12
    min_recipes: int = 3
    seed: int = 0xC0FFEE
    # Bias: how often to target gap-cell families when proposing ADDs.
    gap_bias_p: float = 0.7


# family -> list[recipe ID]
def index_pool_by_family(pool: list[dict]) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for entry in pool:
        out.setdefault(entry.get("family", "core"), []).append(entry["id"])
    return out


def _gap_families(gap_cells: list[tuple[int, str]]) -> set[str]:
    return {family for _, family in gap_cells}


class HillClimber:
    """Drives the search. Pure with respect to fitness — the orchestrator
    plugs in a function that takes a Candidate and returns its corpus
    score. That keeps the search loop testable without Docker."""

    def __init__(
        self,
        pool: list[dict],
        seed_candidate: Candidate,
        fitness: Callable[[Candidate], "CorpusScoreLike"],
        cfg: MutatorConfig | None = None,
    ):
        self.pool = pool
        self.pool_ids = [e["id"] for e in pool]
        self.by_family = index_pool_by_family(pool)
        self.current = seed_candidate
        self.fitness = fitness
        self.cfg = cfg or MutatorConfig()
        self.rng = random.Random(self.cfg.seed)
        self.history: list[dict] = []

    # ----- mutation operators -----

    def _recipe_family(self, recipe_id: str) -> str:
        for entry in self.pool:
            if entry["id"] == recipe_id:
                return entry.get("family", "core")
        return "core"

    def op_add(self, cand: Candidate, gap_families: set[str]) -> Candidate | None:
        if len(cand.recipes) >= self.cfg.max_recipes:
            return None
        unused = [r for r in self.pool_ids if r not in cand.recipes]
        if not unused:
            return None
        if gap_families and self.rng.random() < self.cfg.gap_bias_p:
            biased = [r for r in unused if self._recipe_family(r) in gap_families]
            if biased:
                unused = biased
        new_recipe = self.rng.choice(unused)
        pos = self.rng.randint(0, len(cand.recipes))
        c = cand.clone()
        c.recipes.insert(pos, new_recipe)
        return c

    def op_remove(self, cand: Candidate, saturated_families: set[str]) -> Candidate | None:
        if len(cand.recipes) <= self.cfg.min_recipes:
            return None
        candidates = list(range(len(cand.recipes)))
        if saturated_families:
            biased = [i for i in candidates
                      if self._recipe_family(cand.recipes[i]) in saturated_families]
            if biased:
                candidates = biased
        idx = self.rng.choice(candidates)
        c = cand.clone()
        del c.recipes[idx]
        return c

    def op_swap(self, cand: Candidate) -> Candidate | None:
        if len(cand.recipes) < 2:
            return None
        i = self.rng.randint(0, len(cand.recipes) - 2)
        c = cand.clone()
        c.recipes[i], c.recipes[i + 1] = c.recipes[i + 1], c.recipes[i]
        return c

    def op_replace(self, cand: Candidate, gap_families: set[str]) -> Candidate | None:
        if not cand.recipes:
            return None
        idx = self.rng.randint(0, len(cand.recipes) - 1)
        target_family = self._recipe_family(cand.recipes[idx])
        # Prefer replacing inside the same family (keep coverage); occasionally
        # cross-family if we have gaps.
        if gap_families and self.rng.random() < 0.3:
            target_family = self.rng.choice(list(gap_families))
        siblings = [r for r in self.by_family.get(target_family, [])
                    if r != cand.recipes[idx] and r not in cand.recipes]
        if not siblings:
            return None
        c = cand.clone()
        c.recipes[idx] = self.rng.choice(siblings)
        return c

    # ----- main loop -----

    def _propose(self, cand: Candidate, gap_families: set[str],
                 saturated_families: set[str]) -> list[Candidate]:
        ops = [
            lambda: self.op_add(cand, gap_families),
            lambda: self.op_remove(cand, saturated_families),
            lambda: self.op_swap(cand),
            lambda: self.op_replace(cand, gap_families),
        ]
        out: list[Candidate] = []
        seen: set[tuple] = {tuple(cand.recipes)}
        while len(out) < self.cfg.proposals_per_iter:
            op = self.rng.choice(ops)
            child = op()
            if child is None:
                continue
            key = tuple(child.recipes)
            if key in seen:
                continue
            seen.add(key)
            out.append(child)
        return out

    def run(self) -> dict:
        cur_score = self.fitness(self.current)
        best = self.current
        best_fit = cur_score
        consec_no_improve = 0

        self.history.append({
            "iter": 0,
            "kind": "seed",
            "score": cur_score.score,
            "recipes": list(self.current.recipes),
        })

        for it in range(1, self.cfg.max_iter + 1):
            gap_families = _gap_families(getattr(best_fit, "gap_cells", lambda: [])())
            # saturated = cells already at >=0.85 score; their family
            # is the prime suspect for over-aggressive recipes
            sat = set()
            per_cell = getattr(best_fit, "per_cell", {}) or {}
            for (_, fam), s in per_cell.items():
                if s >= 0.85:
                    sat.add(fam)

            proposals = self._propose(best, gap_families, sat)
            evaluations = []
            for p in proposals:
                f = self.fitness(p)
                evaluations.append((p, f))

            evaluations.sort(key=lambda x: x[1].score, reverse=True)
            top_cand, top_fit = evaluations[0]

            improved = top_fit.score > best_fit.score + self.cfg.plateau_eps
            self.history.append({
                "iter": it,
                "kind": "step",
                "considered": [
                    {"score": f.score, "recipes": list(c.recipes)}
                    for c, f in evaluations
                ],
                "winner": list(top_cand.recipes) if improved else list(best.recipes),
                "best_score": top_fit.score if improved else best_fit.score,
                "improved": improved,
            })

            if improved:
                best, best_fit = top_cand, top_fit
                consec_no_improve = 0
            else:
                consec_no_improve += 1
                if consec_no_improve >= self.cfg.plateau_window:
                    break

        return {
            "best": list(best.recipes),
            "best_score": best_fit.score,
            "iterations_run": len(self.history) - 1,
            "history": self.history,
        }


# ----- helpers used by the orchestrator -----

def load_pool(path: Path) -> list[dict]:
    import yaml
    return yaml.safe_load(path.read_text())


def load_seed(path: Path) -> Candidate:
    import yaml
    data = yaml.safe_load(path.read_text())
    return Candidate(recipes=list(data["active_recipes"]))


def render_rewrite_yml(cand: Candidate, recipe_name: str) -> str:
    """Wrap the recipe list as an OpenRewrite composite recipe YAML
    suitable for `-Drewrite.configLocation=rewrite.yml` invocation."""
    lines = [
        "type: specs.openrewrite.org/v1beta/recipe",
        f"name: {recipe_name}",
        "displayName: Candidate composite recipe (fitness loop)",
        "description: Auto-generated by the ralph mutator.",
        "recipeList:",
    ]
    for r in cand.recipes:
        lines.append(f"  - {r}")
    return "\n".join(lines) + "\n"
