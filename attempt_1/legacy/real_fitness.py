"""Real fitness function — runs mvn rewrite:run for each micro-repo,
then javac on the result. Returns per-repo metrics and a CorpusScore
shaped like the Docker runner would produce.

This is invoked once per candidate by the ralph loop driver. The loop
driver re-evaluates the seed each restart so RNG variance doesn't get
free credit.

Per-repo evaluation:
  1. Reset source file to the canonical 'before' state.
  2. Write the candidate's recipe list to /tmp/rewrite-candidate.yml.
  3. mvn -o ... rewrite-maven-plugin:5.43.0:run     (the real run)
  4. javac --release 21 ...                        (build_post check)
  5. diff old vs new bytes                          (recipe_applied + sanity)

Aggregates into a CorpusScore identical in shape to scorer.aggregate.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from orchestrator.scorer import RepoScore, aggregate, WEIGHTS


CORPUS = Path("/tmp/real-corpus")
JDK21 = "/tmp/jdk-21.0.4+7"
MVN = "/tmp/apache-maven-3.9.9/bin/mvn"
PLUGIN_GAV = "org.openrewrite.maven:rewrite-maven-plugin:5.43.0:run"
RECIPE_YML = Path("/tmp/rewrite-candidate.yml")

# Canonical "before" state for each repo. Reset before each eval so
# candidates are scored against an identical starting point.
BEFORE_STATE: dict[str, dict[str, str]] = {
    "j8-unused": {
        "src/main/java/demo/A.java":
            "package demo;\n"
            "import java.util.List;\n"
            "import java.util.ArrayList;\n"
            "import java.util.Map;\n"
            "public class A { public String x() { return \"y\"; } }\n"
    },
    "j11-order": {
        "src/main/java/demo/B.java":
            "package demo;\n"
            "import java.util.Set;\n"
            "import java.io.File;\n"
            "import java.util.Map;\n"
            "import java.io.IOException;\n"
            "public class B { Set<String> s; Map<String,String> m; File f; }\n"
    },
    "j17-trailing": {
        "src/main/java/demo/C.java":
            "package demo;\n"
            "\n"
            "public class C {   \n"
            "    public String name() {     \n"
            "        return \"c\";    \n"
            "    }\n"
            "}\n"
    },
    "j8-clean": {
        "src/main/java/demo/D.java":
            "package demo;\n"
            "\n"
            "public class D {\n"
            "\n"
            "    public String x() {\n"
            "        return \"y\";\n"
            "    }\n"
            "}\n"
    },
}

# Each repo's metadata for the per-cell aggregation.
REPO_META: dict[str, dict] = {
    "j8-unused":   {"java_version": 8,  "dependency_family": "core-cleanup",
                    "needs_recipes": {"org.openrewrite.java.RemoveUnusedImports"}},
    "j11-order":   {"java_version": 11, "dependency_family": "core-cleanup",
                    "needs_recipes": {"org.openrewrite.java.OrderImports"}},
    "j17-trailing":{"java_version": 17, "dependency_family": "core-format",
                    "needs_recipes": {"org.openrewrite.java.format.RemoveTrailingWhitespace",
                                      "org.openrewrite.java.format.AutoFormat",
                                      "org.openrewrite.java.IntelliJ",
                                      "org.openrewrite.java.SpringFormat"}},
    "j8-clean":    {"java_version": 8,  "dependency_family": "core-baseline",
                    "needs_recipes": set()},   # nothing should touch it
}


def reset_corpus() -> None:
    for repo_id, files in BEFORE_STATE.items():
        for rel, content in files.items():
            (CORPUS / repo_id / rel).write_text(content)
        # remove target/ so javac compiles fresh
        target = CORPUS / repo_id / "target"
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)


def write_candidate(recipes: list[str]) -> None:
    body = "type: specs.openrewrite.org/v1beta/recipe\nname: x.Candidate\nrecipeList:\n"
    for r in recipes:
        body += f"  - {r}\n"
    RECIPE_YML.write_text(body)


def _file_bytes(repo_id: str) -> bytes:
    parts = []
    for rel in BEFORE_STATE[repo_id]:
        parts.append((CORPUS / repo_id / rel).read_bytes())
    return b"".join(parts)


def _javac_compile(repo_id: str) -> bool:
    """Compile post-recipe source with Java 21. Return True if it compiles."""
    repo = CORPUS / repo_id
    sources = list(repo.glob("src/main/java/**/*.java"))
    if not sources:
        return False
    out = repo / "target" / "classes-j21"
    out.mkdir(parents=True, exist_ok=True)
    cmd = [f"{JDK21}/bin/javac", "--release", "21", "-d", str(out),
           *[str(s) for s in sources]]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=15)
        return r.returncode == 0
    except subprocess.TimeoutExpired:
        return False


def _run_rewrite_on(repo_id: str) -> tuple[int, float]:
    """Run mvn rewrite:run in repo. Returns (returncode, wall_seconds)."""
    repo = CORPUS / repo_id
    cmd = [
        MVN, "-o", "-B", "-ntp", "-q", PLUGIN_GAV,
        "-Drewrite.activeRecipes=x.Candidate",
        f"-Drewrite.configLocation={RECIPE_YML}",
    ]
    t0 = time.monotonic()
    try:
        r = subprocess.run(cmd, cwd=str(repo), capture_output=True, timeout=30,
                           env={**os.environ,
                                "JAVA_HOME": JDK21,
                                "PATH": f"{JDK21}/bin:" + os.environ["PATH"],
                                "MAVEN_OPTS":
                                    "-Xmx384m -Dorg.slf4j.simpleLogger.defaultLogLevel=error"})
        rc = r.returncode
    except subprocess.TimeoutExpired:
        rc = 124
    return rc, time.monotonic() - t0


def score_one_repo(repo_id: str) -> RepoScore:
    """Run candidate against one repo, score it."""
    meta = REPO_META[repo_id]
    before = _file_bytes(repo_id)

    rc, elapsed = _run_rewrite_on(repo_id)

    after = _file_bytes(repo_id)
    changed = (before != after)
    build_pass = _javac_compile(repo_id)

    # build_pass component
    build_score = 1.0 if build_pass else 0.0

    # test_pass: this corpus has no tests, treat as build_pass tier
    test_score = build_score

    # recipe_applied: did SOMETHING change? Note: for j8-clean,
    # "applied" is a NEGATIVE — we want to NOT touch it. So we flip.
    if meta["dependency_family"] == "core-baseline":
        recipe_applied = 0.0 if changed else 1.0   # untouched is good
    else:
        # For repos that have an issue we want fixed, "changed" is good
        # AND additionally we want the relevant recipe family to be present
        # in the candidate.
        recipe_applied = 1.0 if changed else 0.0

    # diff_sanity: if changed too aggressively (e.g. file shrank by >70%
    # or grew >50%), penalise
    if not changed:
        diff_sanity = 1.0
    else:
        ratio = len(after) / max(1, len(before))
        if 0.3 <= ratio <= 1.5:
            diff_sanity = 1.0
        elif 0.15 <= ratio <= 2.0:
            diff_sanity = 0.7
        else:
            diff_sanity = 0.3

    composite = (
        WEIGHTS["build"] * build_score
        + WEIGHTS["test"] * test_score
        + WEIGHTS["diff_sanity"] * diff_sanity
        + WEIGHTS["recipe_applied"] * recipe_applied
    )

    return RepoScore(
        repo_id=repo_id,
        java_version=meta["java_version"],
        dependency_family=meta["dependency_family"],
        build_pass=build_score,
        test_pass=test_score,
        diff_sanity=diff_sanity,
        recipe_applied=recipe_applied,
        composite=composite,
        raw={"real": True, "rc": rc, "elapsed": elapsed,
             "changed": changed, "build_pass": build_pass},
    )


def score_candidate(recipes: list[str], parallel: int = 4) -> "CorpusScoreLike":
    """The fitness function."""
    reset_corpus()
    write_candidate(recipes)

    repo_ids = list(REPO_META.keys())
    results: list[RepoScore] = []
    with ThreadPoolExecutor(max_workers=parallel) as ex:
        futures = {ex.submit(score_one_repo, rid): rid for rid in repo_ids}
        for fut in as_completed(futures):
            results.append(fut.result())

    return aggregate(results)


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("--recipes", nargs="*", required=True)
    args = p.parse_args()
    corpus = score_candidate(args.recipes)
    print(json.dumps({
        "score": corpus.score,
        "per_cell": {f"{j}:{f}": s for (j, f), s in corpus.per_cell.items()},
        "per_repo": [
            {"id": r.repo_id, "composite": r.composite,
             "build_pass": r.build_pass, "recipe_applied": r.recipe_applied,
             "diff_sanity": r.diff_sanity, "raw": r.raw}
            for r in corpus.per_repo
        ],
    }, indent=2))
