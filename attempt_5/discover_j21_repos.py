"""attempt_5: search GitHub for currently-J21 Maven repos, then walk pom history
to find their J8/J11/J17 commits.

Each GitHub Code Search query returns at most 1000 results. To break the cap we
shard by repo size (in KB). Authenticated requests have higher rate limits.

Output: attempt_5/lineage_candidates_v2.json (merged with v1's 56).
"""
import json, os, time, subprocess, re, collections, tempfile, shutil
import urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

HERE = "/home/vmihaylov/java_8_11_17_to_java_21"
TOKEN = os.environ.get("GITHUB_TOKEN", "")  # optional but recommended

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


def gh_search_code(query, per_page=100, max_pages=10):
    """GitHub Code Search; returns list of {repo: 'owner/name', path: 'pom.xml'}."""
    results = []
    for page in range(1, max_pages + 1):
        url = f"https://api.github.com/search/code?q={urllib.parse.quote(query)}&per_page={per_page}&page={page}"
        req = urllib.request.Request(url, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {TOKEN}" if TOKEN else "",
            "User-Agent": "j21-lineage-discovery",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.loads(r.read())
        except Exception as e:
            print(f"  query page {page} failed: {e}", flush=True)
            break
        items = data.get("items", [])
        if not items:
            break
        for it in items:
            results.append({
                "repo": it["repository"]["full_name"],
                "path": it["path"],
            })
        if len(items) < per_page:
            break
        time.sleep(2)  # respect rate limits
    return results


def walk_history(repo_full):
    """Reuse the same git-log walker as discover_lineage.py."""
    tmp = tempfile.mkdtemp(prefix="lineage-")
    try:
        url = f"https://github.com/{repo_full}.git"
        r = subprocess.run(
            ["git", "clone", "--filter=blob:none", "--no-checkout", url, f"{tmp}/repo"],
            capture_output=True, timeout=90,
        )
        if r.returncode != 0:
            return None
        r = subprocess.run(
            ["git", "log", "--all", "--format=%H", "--", "pom.xml"],
            cwd=f"{tmp}/repo", capture_output=True, timeout=30,
        )
        if r.returncode != 0:
            return None
        shas = r.stdout.decode().split()
        if not shas:
            return None
        chain = []
        seen = set()
        for sha in reversed(shas):
            rr = subprocess.run(
                ["git", "show", f"{sha}:pom.xml"],
                cwd=f"{tmp}/repo", capture_output=True, timeout=15,
            )
            if rr.returncode != 0:
                continue
            ver = extract_java_version(rr.stdout.decode(errors="replace"))
            if ver and ver not in seen:
                chain.append((sha, ver))
                seen.add(ver)
        return chain
    except subprocess.TimeoutExpired:
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    # Several queries to break the 1000-result-per-query cap; shard by size buckets.
    queries = [
        '"<java.version>21</java.version>" extension:xml filename:pom size:<5000',
        '"<java.version>21</java.version>" extension:xml filename:pom size:5000..20000',
        '"<java.version>21</java.version>" extension:xml filename:pom size:>20000',
        '"<maven.compiler.release>21</maven.compiler.release>" extension:xml filename:pom',
        '"<release>21</release>" extension:xml filename:pom',
    ]
    all_repos = set()
    for q in queries:
        print(f"query: {q}", flush=True)
        items = gh_search_code(q, per_page=100, max_pages=10)
        added = 0
        for it in items:
            if it["repo"] not in all_repos:
                all_repos.add(it["repo"])
                added += 1
        print(f"  +{added} new, total={len(all_repos)}", flush=True)
        time.sleep(3)

    print(f"\ncandidate J21 repos: {len(all_repos)}", flush=True)

    # Walk each repo's history
    lineages = []
    by_oldest = collections.Counter()
    done = [0]
    lock = Lock()

    def worker(repo):
        chain = walk_history(repo)
        with lock:
            done[0] += 1
            if chain and len(chain) >= 2:
                e = {
                    "repo_full_name": repo,
                    "owner": repo.split("/")[0],
                    "lineage": [{"java_version": int(v), "commit_sha": s} for s, v in chain],
                    "oldest_java_version": int(chain[0][1]),
                    "newest_java_version": int(chain[-1][1]),
                }
                lineages.append(e)
                by_oldest[int(chain[0][1])] += 1
            if done[0] % 25 == 0:
                j21 = sum(1 for x in lineages if x["newest_java_version"] == 21)
                print(f"  walked {done[0]}/{len(all_repos)}, lineages={len(lineages)}, reach-J21={j21}", flush=True)

    with ThreadPoolExecutor(max_workers=16) as ex:
        list(ex.map(worker, list(all_repos)))

    j21_reaching = [e for e in lineages if e["newest_java_version"] == 21]
    print(f"\ntotal lineages: {len(lineages)}, reaching J21: {len(j21_reaching)}")
    print("by oldest:")
    for k in sorted(by_oldest):
        print(f"  J{k}: {by_oldest[k]}")

    out = f"{HERE}/attempt_5/lineage_candidates_v2.json"
    json.dump(lineages, open(out, "w"), indent=2)
    print(f"saved to {out}")


if __name__ == "__main__":
    main()
