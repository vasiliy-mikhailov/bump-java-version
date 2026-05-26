"""attempt_8 dataset builder — commit-distance variant (per refined ff #2).

For each (repo, jv_from → jv_to=21) stage pulled from the existing lineage corpus:
  1. Find `next_version_commit` = first commit at the next Java version after jv_from
     in this repo's lineage (may or may not be jv_to itself).
  2. Walk back N commits from next_version_commit along --first-parent.
     This commit is solidly in the jv_from era, past the maintainer's prep window.
  3. Probe at increasing distances N ∈ {20, 50, 100, 200}. For each candidate:
       mvn compile under jv_from   (must pass)
       mvn test under jv_from      (pass-rate must be ≥ min_pass_rate, default 0.7)
  4. Accept the smallest distance that meets the bar.

A repo with full lineage J8→J11→J17→J21 contributes THREE stages:
  (J8→J21)  sha_from = step-back from first_J11_commit
  (J11→J21) sha_from = step-back from first_J17_commit
  (J17→J21) sha_from = step-back from first_J21_commit

All three share the same sha_to (the J21-bump commit).

Cache-only commit/SHA discovery via /var/cache/git-mirrors. Probe checkouts use
shallow_fetch (1 SHA per probe). No full GitHub clones.

Output: attempt_8/dataset_yearback.json  (selected stages)
        attempt_8/dataset_yearback_skipped.json  (with reason + probes tried)
"""
import os, sys, json, time, argparse, subprocess, tempfile, shutil, threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "/home/vmihaylov/java_8_11_17_to_java_21/attempt_7/tools")
from run_sequenced_java import docker_phase, shallow_fetch, WORK, BASE
from test_conservation import parse_surefire_dir, clear_surefire

ATTEMPT8 = f"{BASE}/attempt_8"
DEFAULT_INPUT = f"{BASE}/attempt_5/lineage_dataset_v4_final.json"
DEFAULT_OUTPUT = f"{ATTEMPT8}/dataset_yearback.json"
DEFAULT_SKIPPED = f"{ATTEMPT8}/dataset_yearback_skipped.json"
DEFAULT_PERSTAGE_DIR = f"{ATTEMPT8}/yearback_probes"
CACHE_DIR = "/var/cache/git-mirrors"

os.makedirs(ATTEMPT8, exist_ok=True)
os.makedirs(DEFAULT_PERSTAGE_DIR, exist_ok=True)


def cache_path(repo):
    return f"{CACHE_DIR}/{repo}.git"


def have_cache(repo):
    p = cache_path(repo)
    return os.path.isdir(p) and os.path.isfile(os.path.join(p, "HEAD"))


def commit_back(repo, anchor_sha, n):
    """Return the sha that is n commits back from anchor_sha along --first-parent.
    None if the history isn't long enough."""
    r = subprocess.run(
        ["git", "-C", cache_path(repo), "rev-list", "--first-parent",
         f"-n", "1", f"--skip={n}", anchor_sha],
        capture_output=True, timeout=30,
    )
    if r.returncode != 0: return None
    sha = r.stdout.decode().strip()
    return sha or None


def probe_candidate(repo, sha, jv_from, timeout_each=900):
    """Shallow-fetch sha, run mvn compile then mvn test under jv_from.
    Returns (compile_ok, passed_count, failed_count, note)."""
    work = tempfile.mkdtemp(prefix="yb_probe_", dir=WORK)
    rdir = tempfile.mkdtemp(prefix="yb_r_", dir=WORK)
    logs = tempfile.mkdtemp(prefix="yb_l_", dir=WORK)
    try:
        if not shallow_fetch(repo, sha, work):
            return (False, 0, 0, "fetch_failed")
        rc_c, _ = docker_phase(work, rdir, logs, "build_pre", jv_from, timeout=timeout_each)
        if rc_c != 0:
            return (False, 0, 0, f"compile_rc={rc_c}")
        clear_surefire(work)
        rc_t, _ = docker_phase(work, rdir, logs, "test_pre", jv_from, timeout=timeout_each)
        passed, failed = parse_surefire_dir(work)
        return (True, len(passed), len(failed), f"test_rc={rc_t}")
    finally:
        for d in (work, rdir, logs):
            shutil.rmtree(d, ignore_errors=True)


def best_sha_from(stage, distances, min_pass_rate):
    """For one stage, probe candidates at increasing commit-distance back from next_version_commit."""
    repo = stage["repo"]
    next_version_commit = stage["next_version_commit"]
    jv_from = stage["jv_from"]
    slug = f"{repo.replace('/', '_')}__J{jv_from}toJ{stage['jv_to']}"
    probe_log = f"{DEFAULT_PERSTAGE_DIR}/{slug}.json"
    if os.path.exists(probe_log):
        prev = json.load(open(probe_log))
        if prev.get("selected"): return prev

    record = {"slug": slug, "stage": stage, "probes": [], "selected": None, "skipped_reason": None}

    if not have_cache(repo):
        record["skipped_reason"] = "cache_miss"
        json.dump(record, open(probe_log, "w"), indent=2)
        return record

    for n in distances:
        sha = commit_back(repo, next_version_commit, n)
        if not sha:
            record["probes"].append({"distance_back": n, "note": "history_too_short"})
            json.dump(record, open(probe_log, "w"), indent=2)
            break
        compile_ok, p_pass, p_fail, note = probe_candidate(repo, sha, jv_from)
        pass_rate = p_pass / max(1, (p_pass + p_fail))
        probe = {
            "distance_back": n, "sha": sha,
            "compile_ok": compile_ok, "pass": p_pass, "fail": p_fail,
            "pass_rate": round(pass_rate, 3), "note": note,
        }
        record["probes"].append(probe)
        json.dump(record, open(probe_log, "w"), indent=2)
        if compile_ok and p_pass >= 1 and pass_rate >= min_pass_rate:
            record["selected"] = {
                "repo": repo, "sha_from": sha, "sha_to": stage["sha_to"],
                "jv_from": jv_from, "jv_to": stage["jv_to"],
                "next_version_commit": next_version_commit,
                "commits_back": n,
                "pre_pass_count": p_pass, "pre_fail_count": p_fail,
                "pass_rate": round(pass_rate, 3),
                "probes_tried": len(record["probes"]),
            }
            json.dump(record, open(probe_log, "w"), indent=2)
            return record
    if record["selected"] is None:
        record["skipped_reason"] = "no_candidate_met_bar"
    json.dump(record, open(probe_log, "w"), indent=2)
    return record


def gather_j21_stages(corpus):
    """Emit ALL (jv_from → J21) stages per repo.

    For each repo whose verified_lineage contains J21, every Java version earlier
    in the lineage produces a stage. `next_version_commit` for that stage is the
    commit at the NEXT version in this repo's lineage (which may or may not be J21).
    `sha_to` is always the J21-bump commit.
    """
    stages = []
    for e in corpus:
        vl = sorted(e["verified_lineage"], key=lambda s: s["java_version"])
        j21_idx = next((k for k, s in enumerate(vl) if s["java_version"] == 21), None)
        if j21_idx is None: continue
        sha_to = vl[j21_idx]["commit_sha"]
        # Emit one stage per Java version that comes BEFORE J21 in the lineage
        for k in range(j21_idx):
            stages.append({
                "repo": e["repo_full_name"],
                "sha_to": sha_to,
                "next_version_commit": vl[k + 1]["commit_sha"],
                "jv_from": vl[k]["java_version"],
                "jv_to": 21,
                "lineage_versions": [s["java_version"] for s in vl],
            })
    return stages


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=DEFAULT_INPUT)
    ap.add_argument("--output", default=DEFAULT_OUTPUT)
    ap.add_argument("--skipped", default=DEFAULT_SKIPPED)
    ap.add_argument("--distances", default="20,50,100,200", help="comma-sep commit-distances to probe")
    ap.add_argument("--min-pass-rate", type=float, default=0.7)
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--filter-slug", default="")
    args = ap.parse_args()

    corpus = json.load(open(args.input))
    stages = gather_j21_stages(corpus)
    if args.filter_slug:
        import fnmatch
        stages = [s for s in stages if fnmatch.fnmatchcase(
            f"{s['repo'].replace('/', '_')}__J{s['jv_from']}toJ{s['jv_to']}",
            args.filter_slug)]
    if args.limit: stages = stages[:args.limit]
    distances = [int(d.strip()) for d in args.distances.split(",")]

    n = len(stages)
    print(f"=== {n} (repo, jv_from→J21) stages to probe ===", flush=True)
    by_jvf = {}
    for s in stages: by_jvf[s["jv_from"]] = by_jvf.get(s["jv_from"], 0) + 1
    print(f"  by jv_from: {dict(sorted(by_jvf.items()))}", flush=True)
    print(f"  distances per probe: {distances}", flush=True)

    done = [0]; lock = threading.Lock()
    def go(s):
        slug = f"{s['repo'].replace('/', '_')}__J{s['jv_from']}toJ{s['jv_to']}"
        try:
            rec = best_sha_from(s, distances, args.min_pass_rate)
        except Exception as e:
            rec = {"slug": slug, "stage": s, "skipped_reason": f"EXC:{type(e).__name__}:{e}"}
        with lock:
            done[0] += 1
            sel = rec.get("selected")
            if sel:
                print(f"  [{done[0]:3d}/{n}] {slug}: SELECTED commits_back={sel['commits_back']} pre={sel['pre_pass_count']}p/{sel['pre_fail_count']}f rate={sel['pass_rate']}", flush=True)
            else:
                print(f"  [{done[0]:3d}/{n}] {slug}: SKIPPED reason={rec.get('skipped_reason')}", flush=True)
        return rec

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        all_recs = list(ex.map(go, stages))

    selected = [r["selected"] for r in all_recs if r.get("selected")]
    skipped = [{"slug": r["slug"], "reason": r.get("skipped_reason"),
                "probes": r.get("probes", [])} for r in all_recs if not r.get("selected")]
    json.dump(selected, open(args.output, "w"), indent=2)
    json.dump(skipped, open(args.skipped, "w"), indent=2)
    print()
    print("=== SUMMARY ===")
    print(f"  selected stages: {len(selected)}/{n} ({100*len(selected)/max(1,n):.1f}%)")
    print(f"  skipped:         {len(skipped)}")
    by_jvf_sel = {}
    for s in selected: by_jvf_sel[s["jv_from"]] = by_jvf_sel.get(s["jv_from"], 0) + 1
    print(f"  selected by jv_from: {dict(sorted(by_jvf_sel.items()))}")
    print(f"  saved → {args.output}")


if __name__ == "__main__":
    main()
