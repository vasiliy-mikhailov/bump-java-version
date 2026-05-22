"""Tests for the hill-climbing mutator using a synthetic fitness function."""

import unittest
from dataclasses import dataclass, field

from orchestrator.mutator import (
    Candidate,
    HillClimber,
    MutatorConfig,
    render_rewrite_yml,
)


POOL = [
    {"id": "rA", "family": "core"},
    {"id": "rB", "family": "core"},
    {"id": "rC", "family": "spring-boot"},
    {"id": "rD", "family": "spring-boot"},
    {"id": "rE", "family": "junit"},
    {"id": "rF", "family": "junit"},
    {"id": "rG", "family": "jakarta"},
    {"id": "rH", "family": "hibernate"},
    {"id": "rI", "family": "cleanup"},
    {"id": "rJ", "family": "cleanup"},
]


@dataclass
class FakeScore:
    score: float
    per_cell: dict = field(default_factory=dict)

    def gap_cells(self):
        return [k for k, v in self.per_cell.items() if v < 0.6]


def wanted_fitness(target: set[str]):
    """Returns a fitness function that maximises overlap with `target`,
    penalising long compositions slightly."""
    def f(cand: Candidate) -> FakeScore:
        hits = sum(1 for r in cand.recipes if r in target)
        base = hits / max(len(target), 1)
        penalty = max(0, len(cand.recipes) - len(target)) * 0.05
        return FakeScore(score=max(0.0, min(1.0, base - penalty)),
                         per_cell={(8, "core"): max(0.0, base - penalty)})
    return f


class MutatorTests(unittest.TestCase):
    def test_seed_score_is_first_history_entry(self):
        seed = Candidate(recipes=["rA", "rB"])
        hc = HillClimber(POOL, seed, fitness=wanted_fitness({"rA"}),
                         cfg=MutatorConfig(max_iter=1, proposals_per_iter=2,
                                           seed=1))
        result = hc.run()
        self.assertEqual(result["history"][0]["kind"], "seed")

    def test_converges_toward_target_set(self):
        target = {"rC", "rE", "rG", "rH"}
        seed = Candidate(recipes=["rA", "rB"])
        hc = HillClimber(POOL, seed, fitness=wanted_fitness(target),
                         cfg=MutatorConfig(max_iter=20, proposals_per_iter=5,
                                           plateau_eps=0.001, plateau_window=4,
                                           seed=7))
        result = hc.run()
        # The climber should pick up at least 3 of 4 target recipes.
        hits = sum(1 for r in result["best"] if r in target)
        self.assertGreaterEqual(hits, 3, f"only got {hits}/4 — best={result['best']}")

    def test_plateau_stop(self):
        # Fitness function that maxes out immediately so the loop should
        # plateau after `plateau_window` iterations.
        flat = lambda c: FakeScore(score=1.0)
        seed = Candidate(recipes=["rA", "rB", "rC"])
        cfg = MutatorConfig(max_iter=20, proposals_per_iter=3,
                            plateau_window=3, plateau_eps=0.01, seed=2)
        hc = HillClimber(POOL, seed, fitness=flat, cfg=cfg)
        result = hc.run()
        # 1 seed eval + 3 plateau iterations max
        self.assertLessEqual(result["iterations_run"], cfg.plateau_window + 1)

    def test_min_max_recipes_respected(self):
        seed = Candidate(recipes=["rA"])
        cfg = MutatorConfig(max_iter=10, proposals_per_iter=4,
                            min_recipes=2, max_recipes=4, seed=3)
        hc = HillClimber(POOL, seed, fitness=wanted_fitness({"rC", "rD"}),
                         cfg=cfg)
        result = hc.run()
        self.assertGreaterEqual(len(result["best"]), 1)
        self.assertLessEqual(len(result["best"]), cfg.max_recipes)


class RenderTests(unittest.TestCase):
    def test_rewrite_yml_shape(self):
        cand = Candidate(recipes=["x.y.A", "x.y.B"])
        yml = render_rewrite_yml(cand, "com.x.Comp")
        self.assertIn("name: com.x.Comp", yml)
        self.assertIn("- x.y.A", yml)
        self.assertIn("- x.y.B", yml)
        self.assertIn("recipeList:", yml)


if __name__ == "__main__":
    unittest.main()
