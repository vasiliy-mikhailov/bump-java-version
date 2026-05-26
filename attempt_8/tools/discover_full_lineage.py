"""Discover repos with full J8 → J11 → J17 → J21 migration lineage.

Strategy per ff #2: distinct-owner sampling, walk-history. Concretely:
  1. Query GitHub code search for pom.xml files declaring java.version 8, 11, 17, 21 separately.
  2. For each candidate repo, walk git history on pom.xml (cache-only if available, else shallow clone).
  3. Find all distinct Java versions ever declared. Keep repos with {8, 11, 17, 21} subset.
  4. Emit attempt_8/full_lineage_candidates.jsonl with one entry per qualified repo.

GitHub-token-aware. Rate-limit safe (max 30 requests/min, sleeps when low).

Usage:
  discover_full_lineage.py [--token <gh_token>] [--limit 1000] [--workers 4]
"""
import os, sys, json, re, time, subprocess, urllib.request, urllib.parse, tempfile, shutil
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

BASE = "/home/vmihaylov/java_8_11_17_to_java_21"
ATTEMPT8 = f"{BASE}/attempt_8"
os.makedirs(ATTEMPT8, exist_ok=True)
OUT_JSONL = f"{ATTEMPT8}/full_lineage_candidates.jsonl"
CACHE_DIR = "/var/cache/git-mirrors"

TOKEN = os.environ.get("GITHUB_TOKEN", "")
HEADERS = {"Accept": "application/vnd.github+json", "User-Agent": "claude-yearback-disco"}
if TOKEN: HEADERS["Authorization"] = f"Bearer {TOKEN}"

JAVA_VER_RE = re.compile(
    r"<(?:java\.version|maven\.compiler\.source|maven\.compiler\.target|maven\.compiler\.release|source|target|release)>\s*"
    r"(?:1\.)?(\d+)(?:\.\d+)?\s*</",
    re.IGNORECASE,
)

SEARCH_QUERIES = [
    # Repos likely to span 4 Java majors — look for evidence of old + new declarations
    "language:Java filename:pom.xml \"<java.version>8</java.version>\"",
    "language:Java filename:pom.xml \"<java.version>1.8</java.version>\"",
    "language:Java filename:pom.xml \"<maven.compiler.source>8</maven.compiler.source>\"",
    "language:Java filename:pom.xml \"<maven.compiler.release>8</maven.compiler.release>\"",
]


def gh_search_code(query, page=1, per_page=100):
    url = f"https://api.github.com/search/code?q={urllib.parse.quote(query)}&per_page={per_page}&page={page}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 403:
            # rate-limited; back off
            print(f"  rate-limited, sleeping 60s", flush=True)
            time.sleep(60)
            return None
        return None
    except Exception as e:
        return None


def have_cache(repo):
    return os.path.isdir(f"{CACHE_DIR}/{repo}.git")


def java_versions_in_repo(repo):
    """Walk pom.xml history on the cached mirror (no blobs needed) — gather distinct java versions ever declared.
    If the mirror's commit objects are available, this is free. Blobs needed only when content is read."""
    cache = f"{CACHE_DIR}/{repo}.git"
    if not os.path.isdir(cache): return None

    # Get every pom.xml commit + sha
    r = subprocess.run(
        ["git", "-C", cache, "log", "--all", "--diff-filter=AM", "--name-only",
         "--format=COMMIT %H", "--", "pom.xml"],
        capture_output=True, timeout=60,
    )
    if r.returncode != 0: return None
    versions = set()
    current_sha = None
    # Cap how many commits to inspect (most recent N)
    inspected = 0; cap = 50
    for ln in r.stdout.decode(errors="replace").splitlines():
        if ln.startswith("COMMIT "):
            current_sha = ln.split()[1]
            inspected += 1
            if inspected > cap: break
            continue
        if ln.strip() == "pom.xml" and current_sha:
            # Read pom.xml at that sha — blob may be missing in partial clone
            rb = subprocess.run(
                ["git", "-C", cache, "show", f"{current_sha}:pom.xml"],
                capture_output=True, timeout=10,
            )
            if rb.returncode == 0:
                content = rb.stdout.decode(errors="replace", )
                for m in JAVA_VER_RE.finditer(content):
                    try:
                        v = int(m.group(1))
                        if v in (7, 8, 11, 17, 21): versions.add(v)
                    except Exception: continue
    return versions if versions else set()


def process_one(repo, lock, seen):
    with lock:
        if repo in seen: return None
        seen.add(repo)
    if not have_cache(repo):
        return {"repo": repo, "status": "no_cache"}
    vs = java_versions_in_repo(repo)
    if vs is None:
        return {"repo": repo, "status": "history_walk_failed"}
    has_full = {8, 11, 17, 21}.issubset(vs)
    return {"repo": repo, "status": "full_lineage" if has_full else "partial_lineage",
            "versions": sorted(vs)}


def candidate_repos_from_search(query, max_pages=10):
    """Yield repo full names from a GitHub code search."""
    for page in range(1, max_pages + 1):
        d = gh_search_code(query, page=page)
        if not d: break
        items = d.get("items", [])
        if not items: break
        for it in items:
            full = it.get("repository", {}).get("full_name")
            if full: yield full
        if len(items) < 100: break
        time.sleep(2)  # rate-friendly


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--cache-only", action="store_true",
                    help="don't query GitHub at all; only scan existing /var/cache/git-mirrors")
    args = ap.parse_args()

    seen = set()
    lock = Lock()
    results = []

    if args.cache_only:
        # Walk every cached repo (no GitHub calls at all)
        all_cached = []
        for owner in sorted(os.listdir(CACHE_DIR)):
            for repo_dir in sorted(os.listdir(f"{CACHE_DIR}/{owner}")):
                if not repo_dir.endswith(".git"): continue
                all_cached.append(f"{owner}/{repo_dir[:-4]}")
        print(f"=== cache-only mode: {len(all_cached)} cached repos to inspect ===", flush=True)
        candidates = all_cached[: args.limit]
    else:
        # GitHub search to gather candidates
        candidates = []
        for q in SEARCH_QUERIES:
            print(f"=== search: {q[:60]} ===", flush=True)
            for repo in candidate_repos_from_search(q):
                candidates.append(repo)
                if len(candidates) >= args.limit: break
            if len(candidates) >= args.limit: break

    print(f"=== {len(candidates)} candidate repos to inspect ===", flush=True)
    full_count = 0
    with ThreadPoolExecutor(max_workers=args.workers) as ex, open(OUT_JSONL, "w") as out:
        for r in ex.map(lambda c: process_one(c, lock, seen), candidates):
            if r is None: continue
            results.append(r)
            out.write(json.dumps(r) + "\n"); out.flush()
            if r.get("status") == "full_lineage":
                full_count += 1
                print(f"  + FULL  {r['repo']}  versions={r['versions']}", flush=True)
    print(f"\n=== DONE: {full_count} full-lineage repos found, {len(results)} total inspected ===")
    print(f"  saved → {OUT_JSONL}")


if __name__ == "__main__":
    main()
