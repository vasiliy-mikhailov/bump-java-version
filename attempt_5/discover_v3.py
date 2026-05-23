"""attempt_5 v3: scale lineage discovery.

Improvements over v1/v2:
  - Multi-module pom walk: track every pom.xml at any depth (large repos)
  - More GitHub Code Search queries: shard by stars + size + dep-family signals
  - Cell classifier: detect dep family (hibernate-5, jakarta-ee-javax, junit4-mockito, spring-boot-2)
    at each lineage's oldest commit; tag the lineage with that cell
  - Per-walk timeout so 3 slow repos don't hang the sweep
"""
import json, os, time, subprocess, re, collections, tempfile, shutil
import urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

HERE = "/home/vmihaylov/java_8_11_17_to_java_21"
TOKEN = os.environ.get("GITHUB_TOKEN", "")

JAVA_VER_RE = re.compile(
    r"<(?:java\.version|maven\.compiler\.source|maven\.compiler\.target|maven\.compiler\.release|source)>([\d.]+)</",
    re.IGNORECASE,
)

# dep family detectors — regex over pom.xml text
FAMILY_SIGNALS = {
    "hibernate-5": [
        re.compile(r"<artifactId>hibernate-core</artifactId>\s*<version>5\."),
        re.compile(r"<hibernate\.version>5\."),
    ],
    "jakarta-ee-javax": [
        re.compile(r"<artifactId>javax\.servlet-api</artifactId>"),
        re.compile(r"<artifactId>javax\.persistence-api</artifactId>"),
        re.compile(r"<groupId>javax\."),
    ],
    "junit4-mockito": [
        re.compile(r"<artifactId>junit</artifactId>\s*<version>4\."),
        re.compile(r"<artifactId>mockito-core</artifactId>"),
        re.compile(r"<artifactId>mockito-all</artifactId>"),
    ],
    "spring-boot-2": [
        re.compile(r"<artifactId>spring-boot-starter-parent</artifactId>\s*<version>2\."),
        re.compile(r"<spring-boot\.version>2\."),
        re.compile(r"<artifactId>spring-boot[^<]*</artifactId>\s*<version>2\."),
    ],
}

def extract_java_version(pom_text):
    for m in JAVA_VER_RE.finditer(pom_text):
        v = m.group(1)
        if v.startswith("1."):
            v = v[2:]
        if v in {"8", "11", "17", "21"}:
            return v
    return None


def detect_family(pom_text):
    """Return the first matching family, or None."""
    for fam, patterns in FAMILY_SIGNALS.items():
        for p in patterns:
            if p.search(pom_text):
                return fam
    return None


def gh_search_code(query, per_page=100, max_pages=10):
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
            print(f"  q-page{page} failed: {e}", flush=True)
            break
        items = data.get("items", [])
        if not items:
            break
        for it in items:
            results.append(it["repository"]["full_name"])
        if len(items) < per_page:
            break
        # GitHub Code Search rate limit: 30 req/min for authenticated
        time.sleep(3)
    return results


def walk_history(repo_full, timeout_s=90):
    """Walk pom history (any depth). Return list of (sha, java_version, pom_text)."""
    tmp = tempfile.mkdtemp(prefix="lineage-")
    try:
        url = f"https://github.com/{repo_full}.git"
        r = subprocess.run(
            ["git", "clone", "--filter=blob:none", "--no-checkout", url, f"{tmp}/repo"],
            capture_output=True, timeout=timeout_s,
        )
        if r.returncode != 0:
            return None
        # All commits that touched ANY pom.xml (any depth)
        r = subprocess.run(
            ["git", "log", "--all", "--format=%H", "--", "*pom.xml"],
            cwd=f"{tmp}/repo", capture_output=True, timeout=30,
        )
        if r.returncode != 0:
            return None
        shas = r.stdout.decode().split()
        if not shas:
            return None
        chain = []
        seen = set()
        # walk chronologically (reverse-chrono → reverse)
        for sha in reversed(shas):
            # find any pom.xml in tree at this sha
            r = subprocess.run(
                ["git", "ls-tree", "-r", "--name-only", sha],
                cwd=f"{tmp}/repo", capture_output=True, timeout=15,
            )
            if r.returncode != 0:
                continue
            poms = [p for p in r.stdout.decode().split() if p.endswith("pom.xml")]
            if not poms:
                continue
            # check pom files for declared Java version; pick the first that gives an answer
            ver = None
            anchor_pom = None
            for pom_path in poms[:5]:  # cap to first 5 pom files to keep it cheap
                rr = subprocess.run(
                    ["git", "show", f"{sha}:{pom_path}"],
                    cwd=f"{tmp}/repo", capture_output=True, timeout=10,
                )
                if rr.returncode != 0:
                    continue
                txt = rr.stdout.decode(errors="replace")
                v = extract_java_version(txt)
                if v:
                    ver = v
                    anchor_pom = (pom_path, txt)
                    break
            if ver and ver not in seen:
                chain.append((sha, ver, anchor_pom))
                seen.add(ver)
        return chain
    except subprocess.TimeoutExpired:
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    # Multiple queries to cover more J21 repos. GitHub caps per-query at 1000 results.
    queries = [
        '"<java.version>21</java.version>" extension:xml filename:pom size:<5000',
        '"<java.version>21</java.version>" extension:xml filename:pom size:5000..10000',
        '"<java.version>21</java.version>" extension:xml filename:pom size:10000..30000',
        '"<java.version>21</java.version>" extension:xml filename:pom size:>30000',
        '"<maven.compiler.release>21</maven.compiler.release>" extension:xml filename:pom',
        '"<maven.compiler.source>21</maven.compiler.source>" extension:xml filename:pom',
        '"<release>21</release>" extension:xml filename:pom',
        # high-star J21 repos likely had history
        '"<java.version>21</java.version>" extension:xml filename:pom stars:>10',
        '"<java.version>21</java.version>" extension:xml filename:pom stars:>50',
        # also catch Spring Boot 3 repos that bumped to J21
        '"spring-boot-starter-parent" "<version>3" "<java.version>21" extension:xml filename:pom',
    ]
    all_repos = set()
    # also seed with v1+v2 candidates so we re-walk and merge cell classification
    for fname in ["lineage_candidates.json", "lineage_candidates_v2.json"]:
        p = f"{HERE}/attempt_5/{fname}"
        if os.path.exists(p):
            for e in json.load(open(p)):
                all_repos.add(e["repo_full_name"])
    print(f"seeded from prior runs: {len(all_repos)}", flush=True)

    for q in queries:
        print(f"query: {q[:80]}", flush=True)
        items = gh_search_code(q, per_page=100, max_pages=10)
        added = sum(1 for r in items if r not in all_repos)
        all_repos.update(items)
        print(f"  +{added} new, total={len(all_repos)}", flush=True)
        time.sleep(4)

    print(f"\ncandidate pool: {len(all_repos)}", flush=True)

    lineages = []
    done = [0]
    lock = Lock()

    def worker(repo):
        chain = walk_history(repo)
        with lock:
            done[0] += 1
            if chain and len(chain) >= 2:
                # classify by family at OLDEST commit's pom
                _, oldest_v, anchor = chain[0]
                family = detect_family(anchor[1]) if anchor else None
                e = {
                    "repo_full_name": repo,
                    "owner": repo.split("/")[0],
                    "lineage": [{"java_version": int(v), "commit_sha": s} for s, v, _ in chain],
                    "oldest_java_version": int(chain[0][1]),
                    "newest_java_version": int(chain[-1][1]),
                    "family_at_oldest": family,
                }
                lineages.append(e)
            if done[0] % 50 == 0:
                j21 = sum(1 for x in lineages if x["newest_java_version"] == 21)
                print(f"  walked {done[0]}/{len(all_repos)}, lineages={len(lineages)}, reach-J21={j21}", flush=True)

    pool = list(all_repos)
    with ThreadPoolExecutor(max_workers=24) as ex:
        list(ex.map(worker, pool))

    # report
    j21 = [e for e in lineages if e["newest_java_version"] == 21]
    print(f"\ntotal lineages: {len(lineages)}, reach-J21: {len(j21)}")
    by_cell = collections.Counter()
    for e in j21:
        by_cell[(e["oldest_java_version"], e["family_at_oldest"])] += 1
    print("by (oldest_java, family):")
    for k in sorted(by_cell, key=lambda x: (x[0], x[1] or "")):
        print(f"  {k}: {by_cell[k]}")

    out = "/tmp/lineage_candidates_v3.json"
    json.dump(lineages, open(out, "w"), indent=2)
    print(f"saved to {out}")


if __name__ == "__main__":
    main()
