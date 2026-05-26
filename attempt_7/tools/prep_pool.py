"""attempt_7: prepare the candidate stage pool.

Filter:
  - iter-0 was actually run (recipe was attempted)
  - iter-0 recipe_applied == True (project parses; OpenRewrite can work with it)
  - iter-0 build_post == False OR tests_post == False  (something is missing — i.e.,
    UpgradeToJava<N> alone wasn't sufficient). These are the stages where a per-stage
    bespoke recipe is genuinely needed.

Output: attempt_7/per_stage/<slug>/{meta.json, diff.txt} for each candidate.
"""
import json, os, subprocess, tempfile, shutil, threading
from concurrent.futures import ThreadPoolExecutor

BASE = "/home/vmihaylov/java_8_11_17_to_java_21"
ATTEMPT7 = f"{BASE}/attempt_7"
STAGES_DIR = f"{ATTEMPT7}/per_stage"
os.makedirs(STAGES_DIR, exist_ok=True)


def cls(x):
    if x.get("recipe_applied") is None and x.get("build_post") and (x.get("tests_post") in (True, None)): return "PASS_no_recipe"
    if x.get("build_pre") and (x.get("tests_pre") in (True, None)) and x.get("recipe_applied") and x.get("build_post") and (x.get("tests_post") in (True, None)): return "FULL_PASS"
    if x.get("recipe_applied") is False: return "recipe_fail"
    if x.get("tests_pre") is False: return "tests_pre_failed"
    if x.get("tests_post") is False: return "broke_tests"
    if x.get("build_post") is False: return "build_post_failed"
    if x.get("build_pre") is False: return "pre_fail"
    return "other"


def is_pass(x): return cls(x) in ("FULL_PASS", "PASS_no_recipe")


def shallow_dual_fetch(repo, sha_from, sha_to, dst):
    try:
        subprocess.run(["git", "init", "-q"], cwd=dst, capture_output=True, timeout=30)
        subprocess.run(["git", "remote", "add", "origin", f"https://github.com/{repo}.git"],
                       cwd=dst, capture_output=True, timeout=10)
        for sha in (sha_from, sha_to):
            r = subprocess.run(["git", "fetch", "--depth=1", "origin", sha],
                               cwd=dst, capture_output=True, timeout=600)
            if r.returncode != 0:
                subprocess.run(["git", "fetch", "origin", sha], cwd=dst, capture_output=True, timeout=900)
        return True
    except Exception:
        return False


def get_diff(repo, sha_from, sha_to):
    work = tempfile.mkdtemp(prefix="prepdiff_")
    try:
        if not shallow_dual_fetch(repo, sha_from, sha_to, work): return ""
        r = subprocess.run(["git", "diff", f"{sha_from}..{sha_to}"],
                           cwd=work, capture_output=True, timeout=120)
        return r.stdout.decode("utf-8", errors="replace")
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    i0 = {(x["repo"], x["sha_from"]): x for x in json.load(open(f"{BASE}/attempt_6/ff4_results_iter0.json"))}
    corpus = json.load(open(f"{BASE}/attempt_5/lineage_dataset_v4_final.json"))

    # All adjacent stages from verified_lineage
    stages = []
    for e in corpus:
        vl = sorted(e["verified_lineage"], key=lambda s: s["java_version"])
        for i in range(len(vl) - 1):
            stages.append({"repo": e["repo_full_name"],
                           "sha_from": vl[i]["commit_sha"],
                           "sha_to":   vl[i + 1]["commit_sha"],
                           "jv_from":  vl[i]["java_version"],
                           "jv_to":    vl[i + 1]["java_version"]})

    # Filter
    keep = []
    skipped = {"missing_iter0": 0, "iter0_passed": 0, "unparseable_pom": 0, "tests_pre_failed": 0}
    for s in stages:
        k = (s["repo"], s["sha_from"])
        if k not in i0:
            skipped["missing_iter0"] += 1; continue
        r = i0[k]
        if is_pass(r):
            skipped["iter0_passed"] += 1; continue
        if cls(r) == "tests_pre_failed":
            skipped["tests_pre_failed"] += 1; continue
        if r.get("recipe_applied") is not True:
            # OpenRewrite couldn't even apply — environmental (unparseable pom). Skip.
            skipped["unparseable_pom"] += 1; continue
        keep.append(s)

    print(f"corpus stages: {len(stages)}")
    print(f"  skipped: {skipped}")
    print(f"  candidates kept: {len(keep)}")

    # Fetch diffs in parallel
    lock = threading.Lock()
    done = [0]

    def go(s):
        slug = f"{s['repo'].replace('/', '_')}__J{s['jv_from']}toJ{s['jv_to']}"
        d = f"{STAGES_DIR}/{slug}"
        os.makedirs(d, exist_ok=True)
        meta_path = f"{d}/meta.json"
        diff_path = f"{d}/diff.txt"
        if os.path.exists(diff_path) and os.path.getsize(diff_path) > 0:
            with lock: done[0] += 1
            return
        diff = get_diff(s["repo"], s["sha_from"], s["sha_to"])
        with lock:
            done[0] += 1
            if done[0] % 20 == 0:
                print(f"  fetched {done[0]}/{len(keep)}", flush=True)
        open(diff_path, "w").write(diff)
        json.dump(s, open(meta_path, "w"), indent=2)

    with ThreadPoolExecutor(max_workers=8) as ex:
        list(ex.map(go, keep))

    # Report size distribution
    sizes = []
    for s in keep:
        slug = f"{s['repo'].replace('/', '_')}__J{s['jv_from']}toJ{s['jv_to']}"
        p = f"{STAGES_DIR}/{slug}/diff.txt"
        if os.path.exists(p): sizes.append(os.path.getsize(p))
    if sizes:
        sizes.sort()
        print(f"\ndiff size distribution (bytes):")
        for q in (0.0, 0.25, 0.5, 0.75, 0.9, 0.99, 1.0):
            idx = min(len(sizes) - 1, int(q * (len(sizes) - 1)))
            print(f"  p{int(q*100):3d}: {sizes[idx]:>10d}")
    print(f"\nready: {STAGES_DIR}")


if __name__ == "__main__":
    main()
