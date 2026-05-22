"""One-step driver for the real ralph loop.

Each invocation:
  1. Loads state from /tmp/real-ralph-state.json (or initialises with seed).
  2. Asks the mutator for K proposals near the current incumbent.
  3. Scores each proposal with real_fitness (real mvn rewrite:run).
  4. Updates the incumbent if any proposal beats it.
  5. Persists state and exits.

Invoke repeatedly to advance the loop. Stops automatically when
3 consecutive calls produce no improvement >= 0.01.
"""

from __future__ import annotations

import json
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from orchestrator.mutator import (
    Candidate, HillClimber, MutatorConfig, RecipeRef,
    index_pool_by_family,
)
from real_fitness import score_candidate


STATE = Path("/tmp/real-ralph-state.json")

# Real, loadable recipe pool (verified in this sandbox).
# Meta-recipes (AutoFormat / IntelliJ / SpringFormat) are excluded
# so the search has to compose atomic recipes itself.
POOL = [
    {"id": "org.openrewrite.java.RemoveUnusedImports",                 "family": "cleanup"},
    {"id": "org.openrewrite.java.OrderImports",                        "family": "cleanup"},
    {"id": "org.openrewrite.java.ShortenFullyQualifiedTypeReferences", "family": "cleanup"},
    {"id": "org.openrewrite.java.NoStaticImport",                      "family": "cleanup"},
    {"id": "org.openrewrite.java.UseStaticImport",                     "family": "cleanup"},
    {"id": "org.openrewrite.java.format.RemoveTrailingWhitespace",     "family": "format"},
    {"id": "org.openrewrite.java.format.EmptyNewlineAtEndOfFile",      "family": "format"},
    {"id": "org.openrewrite.java.format.NormalizeLineBreaks",          "family": "format"},
    {"id": "org.openrewrite.java.format.Spaces",                       "family": "format"},
    {"id": "org.openrewrite.java.format.TabsAndIndents",               "family": "format"},
    {"id": "org.openrewrite.java.format.WrappingAndBraces",            "family": "format"},
]

# Deliberately wrong seed: a single recipe that does nothing on our corpus.
SEED = ["org.openrewrite.java.NoStaticImport"]
K_PROPOSALS = 2
PLATEAU_STOP = 3


def load_state() -> dict:
    if STATE.exists():
        return json.loads(STATE.read_text())
    return {
        "iter": 0,
        "current": list(SEED),
        "current_score": None,
        "history": [],
        "consec_no_improve": 0,
        "stopped": False,
    }


def save_state(s: dict) -> None:
    STATE.write_text(json.dumps(s, indent=2))


def score(recipes: list[str]) -> dict:
    t0 = time.monotonic()
    c = score_candidate(recipes)
    elapsed = time.monotonic() - t0
    return {
        "score": c.score,
        "per_cell": {f"{j}:{f}": s for (j, f), s in c.per_cell.items()},
        "elapsed_s": round(elapsed, 1),
    }


def main() -> int:
    s = load_state()
    if s["stopped"]:
        print(json.dumps({"already_stopped": True,
                          "best": s["current"], "best_score": s["current_score"]}))
        return 0

    # Score the incumbent if we haven't yet (first call).
    if s["current_score"] is None:
        seed_info = score(s["current"])
        s["current_score"] = seed_info["score"]
        s["history"].append({"iter": 0, "kind": "seed",
                             "recipes": list(s["current"]),
                             "score": seed_info["score"],
                             "per_cell": seed_info["per_cell"],
                             "elapsed_s": seed_info["elapsed_s"]})
        save_state(s)
        print(json.dumps({"phase": "seed-eval",
                          "score": seed_info["score"],
                          "elapsed_s": seed_info["elapsed_s"]}))
        return 0

    # Propose K mutations from the incumbent.
    s["iter"] += 1
    rng = random.Random(0xCAFE * s["iter"])
    by_family = index_pool_by_family(POOL)
    cur = Candidate(recipes=list(s["current"]))

    # Use the production mutator's operators directly via a HillClimber.
    cfg = MutatorConfig(proposals_per_iter=K_PROPOSALS, max_iter=1,
                        min_recipes=1, max_recipes=6, seed=rng.randint(1, 10**9))
    # Inject a dummy fitness so HillClimber initialises cleanly; we
    # call its ops directly so the dummy is never invoked.
    hc = HillClimber(POOL, cur, fitness=lambda c: None, cfg=cfg)
    proposals = hc._propose(cur, gap_families=set(), saturated_families=set())

    evals = []
    for p in proposals:
        info = score(p.recipes)
        evals.append({"recipes": list(p.recipes), **info})

    evals.sort(key=lambda x: x["score"], reverse=True)
    best_prop = evals[0]
    improved = best_prop["score"] > s["current_score"] + 0.005

    s["history"].append({"iter": s["iter"], "kind": "step",
                         "considered": evals,
                         "winner": best_prop["recipes"] if improved else list(s["current"]),
                         "best_score": best_prop["score"] if improved else s["current_score"],
                         "improved": improved})
    if improved:
        s["current"] = best_prop["recipes"]
        s["current_score"] = best_prop["score"]
        s["consec_no_improve"] = 0
    else:
        s["consec_no_improve"] += 1
        if s["consec_no_improve"] >= PLATEAU_STOP:
            s["stopped"] = True

    save_state(s)
    print(json.dumps({
        "iter": s["iter"],
        "incumbent_score": s["current_score"],
        "incumbent": s["current"],
        "improved": improved,
        "top_proposal_score": best_prop["score"],
        "evaluated_scores": [e["score"] for e in evals],
        "consec_no_improve": s["consec_no_improve"],
        "stopped": s["stopped"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
