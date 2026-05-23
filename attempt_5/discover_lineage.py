"""attempt_5 lineage discovery.

For each candidate repo:
  1. clone shallow (with history)
  2. walk pom.xml history; at each commit, extract declared Java version
  3. identify the FIRST commit at each Java version (8, 11, 17, 21)
  4. record lineage {repo, j8_sha, j11_sha, j17_sha, j21_sha, ... }

Discovery pool: the existing classified_v2.json (which already covers ~1800
repos with Java declarations + family signals). Plus any J21 repos GitHub
search can surface (deferred to next pass).

Output: attempt_5/lineage_candidates.json

We DON'T baseline-verify here yet — that's a separate step (saves clone time
on repos that don't show all 4 Java versions in history).
"""
import json, os, subprocess, re, collections, tempfile, shutil
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

HERE = "/home/vmihaylov/java_8_11_17_to_java_21"

JAVA_VER_RE = re.compile(
    r"<(?:java\.version|maven\.compiler\.source|maven\.compiler\.target|maven\.compiler\.release|source)>([\d.]+)</",
    re.IGNORECASE,
)

def extract_java_version(pom_text):
    for m in JAVA_VER_RE.finditer(pom_text):
        v = m.group(1)
        if v.startswith("1."):
            v = v[2:]
        if v in {"8", "11", "17", "21"}:
            return v
    return None


def walk_history(repo_full):
    """Return ordered list of (sha, java_version) commits touching pom.xml."""
    tmp = tempfile.mkdtemp(prefix="lineage-")
    try:
        url = f"https://github.com/{repo_full}.git"
        r = subprocess.run(
            ["git", "clone", "--filter=blob:none", "--no-checkout", url, f"{tmp}/repo"],
            capture_output=True, timeout=90,
        )
        if r.returncode != 0:
            return None
        # pom commits, chronological
        r = subprocess.run(
            ["git", "log", "--all", "--format=%H", "--", "pom.xml"],
            cwd=f"{tmp}/repo", capture_output=True, timeout=30,
        )
        if r.returncode != 0:
            return None
        shas = r.stdout.decode().split()
        if not shas:
            return None

        # walk chronologically (reverse of git-log order)
        chain = []
        seen_versions = set()
        for sha in reversed(shas):
            r = subprocess.run(
                ["git", "show", f"{sha}:pom.xml"],
                cwd=f"{tmp}/repo", capture_output=True, timeout=15,
            )
            if r.returncode != 0:
                continue
            pom = r.stdout.decode(errors="replace")
            ver = extract_java_version(pom)
            if ver and ver not in seen_versions:
                chain.append((sha, ver))
                seen_versions.add(ver)
        return chain
    except subprocess.TimeoutExpired:
        return None
    except Exception:
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    cl_path = f"{HERE}/attempt_2/verify/classified_v2.json"
    pool = [r["full_name"] for r in json.load(open(cl_path)) if r.get("full_name")]
    pool = list(set(pool))
    print(f"candidate pool: {len(pool)}", flush=True)

    lineages = []
    by_oldest = collections.Counter()
    by_lineage_length = collections.Counter()
    done = [0]
    lock = Lock()

    def worker(repo):
        chain = walk_history(repo)
        with lock:
            done[0] += 1
            if chain and len(chain) >= 2:
                lineage = {
                    "repo_full_name": repo,
                    "owner": repo.split("/")[0],
                    "lineage": [{"java_version": int(v), "commit_sha": sha} for sha, v in chain],
                    "oldest_java_version": int(chain[0][1]),
                    "newest_java_version": int(chain[-1][1]),
                }
                lineages.append(lineage)
                by_oldest[int(chain[0][1])] += 1
                by_lineage_length[len(chain)] += 1
            if done[0] % 50 == 0:
                print(f"  walked {done[0]}/{len(pool)}, lineages={len(lineages)}", flush=True)

    with ThreadPoolExecutor(max_workers=16) as ex:
        list(ex.map(worker, pool))

    print(f"\ntotal lineages (≥2 Java versions seen): {len(lineages)}")
    print("by oldest Java version:")
    for k in sorted(by_oldest):
        print(f"  J{k}: {by_oldest[k]}")
    print("by lineage length:")
    for k in sorted(by_lineage_length):
        print(f"  {k}-step: {by_lineage_length[k]}")

    # how many have FULL J8→J21 or J11→J21 lineage?
    full_8_21 = [l for l in lineages if l["oldest_java_version"] == 8 and l["newest_java_version"] == 21]
    full_11_21 = [l for l in lineages if l["oldest_java_version"] == 11 and l["newest_java_version"] == 21]
    full_17_21 = [l for l in lineages if l["oldest_java_version"] == 17 and l["newest_java_version"] == 21]
    print(f"\n  J8 → J21 lineages: {len(full_8_21)}")
    print(f"  J11 → J21 lineages: {len(full_11_21)}")
    print(f"  J17 → J21 lineages: {len(full_17_21)}")

    out = f"{HERE}/attempt_5/lineage_candidates.json"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    json.dump(lineages, open(out, "w"), indent=2)
    print(f"\nsaved to {out}")


if __name__ == "__main__":
    main()
