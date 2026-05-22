"""Pure-Python tests for the scorer — no Docker, no network."""

import unittest
from orchestrator.scorer import score_one, aggregate, WEIGHTS


ENTRY = {
    "id": "sb2-j8-1",
    "java_version": 8,
    "dependency_family": "spring-boot-2",
}


class ScoreOneTests(unittest.TestCase):
    def test_full_pass(self):
        m = dict(build_post=1, tests_passed_post=10, tests_total_post=10,
                 recipe_applied=1, diff_files=12, diff_binary_files=0)
        s = score_one(m, ENTRY)
        self.assertAlmostEqual(s.composite,
                               WEIGHTS["build"] + WEIGHTS["test"]
                               + WEIGHTS["diff_sanity"] + WEIGHTS["recipe_applied"])

    def test_build_failed(self):
        m = dict(build_post=0, tests_passed_post=0, tests_total_post=0,
                 recipe_applied=1, diff_files=3, diff_binary_files=0)
        s = score_one(m, ENTRY)
        # build 0, test 0 (no tests ran but build also failed),
        # diff sanity 1 (no binaries touched), recipe applied 1
        expected = 0.0 + 0.0 + WEIGHTS["diff_sanity"] + WEIGHTS["recipe_applied"]
        self.assertAlmostEqual(s.composite, expected)

    def test_binary_files_zero_diff_sanity(self):
        m = dict(build_post=1, tests_passed_post=5, tests_total_post=5,
                 recipe_applied=1, diff_files=4, diff_binary_files=4)
        s = score_one(m, ENTRY)
        self.assertEqual(s.diff_sanity, 0.0)

    def test_empty_recipe_no_changes(self):
        """The degenerate case: build succeeds because nothing changed, but
        recipe_applied=0 should penalise."""
        m = dict(build_post=1, tests_passed_post=8, tests_total_post=8,
                 recipe_applied=0, diff_files=0, diff_binary_files=0)
        s = score_one(m, ENTRY)
        expected = (WEIGHTS["build"] + WEIGHTS["test"]
                    + WEIGHTS["diff_sanity"] + 0.0)
        self.assertAlmostEqual(s.composite, expected)


class AggregateTests(unittest.TestCase):
    def test_cells_are_mean_of_means(self):
        # 2 repos in cell A (scores 0.5, 0.5 -> cell 0.5),
        # 4 repos in cell B (all 1.0 -> cell 1.0).
        # Corpus = (0.5 + 1.0)/2 = 0.75 — not weighted by sample density.
        s_low = score_one(
            dict(build_post=1, tests_passed_post=0, tests_total_post=10,
                 recipe_applied=1, diff_files=1, diff_binary_files=0),
            {"id": "a1", "java_version": 8, "dependency_family": "fam-a"},
        )
        s_low2 = score_one(
            dict(build_post=1, tests_passed_post=0, tests_total_post=10,
                 recipe_applied=1, diff_files=1, diff_binary_files=0),
            {"id": "a2", "java_version": 8, "dependency_family": "fam-a"},
        )
        s_high = [
            score_one(
                dict(build_post=1, tests_passed_post=10, tests_total_post=10,
                     recipe_applied=1, diff_files=1, diff_binary_files=0),
                {"id": f"b{i}", "java_version": 11, "dependency_family": "fam-b"},
            )
            for i in range(4)
        ]
        corpus = aggregate([s_low, s_low2] + s_high)
        cell_a = corpus.per_cell[(8, "fam-a")]
        cell_b = corpus.per_cell[(11, "fam-b")]
        self.assertAlmostEqual(cell_a, s_low.composite)  # both repos identical
        self.assertAlmostEqual(cell_b, 1.0)
        self.assertAlmostEqual(corpus.score, (cell_a + cell_b) / 2)

    def test_gap_detection(self):
        s = score_one(
            dict(build_post=0, tests_passed_post=0, tests_total_post=0,
                 recipe_applied=0, diff_files=0, diff_binary_files=0),
            {"id": "x", "java_version": 17, "dependency_family": "spring-boot-2"},
        )
        corpus = aggregate([s])
        self.assertIn((17, "spring-boot-2"), corpus.gap_cells())


if __name__ == "__main__":
    unittest.main()
