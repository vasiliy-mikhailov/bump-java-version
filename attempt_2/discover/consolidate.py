"""
Consolidate raw GitHub search responses into a single candidate pool.
Output: attempt_2/discover/candidates.json
Each candidate: {family, full_name, owner, repo, size_kb, stars, default_branch, html_url, description}
Family attribution: by which query bucket(s) the repo was found in.
"""
import json, glob, os
from collections import defaultdict

BUCKETS = {
  "sb2-a": "spring-boot-2", "sb2-b": "spring-boot-2", "sb2-c": "spring-boot-2",
  "jak-a": "jakarta-ee-javax", "jak-b": "jakarta-ee-javax", "jak-c": "jakarta-ee-javax",
  "jun-a": "junit4-mockito", "jun-b": "junit4-mockito",
  "hib-a": "hibernate-5", "hib-b": "hibernate-5",
}

pool = {}  # full_name -> dict
fam_by_repo = defaultdict(set)

for f in sorted(glob.glob("attempt_2/discover/raw/*.json")):
    key = os.path.basename(f).split("-p")[0]  # "sb2-a"
    fam = BUCKETS.get(key)
    if not fam:
        continue
    try:
        items = json.load(open(f))["items"]
    except Exception:
        continue
    for r in items:
        if r.get("language") not in (None, "Java", "Kotlin", "Groovy"):
            continue  # skip non-JVM
        if r.get("archived") or r.get("fork"):
            continue
        size_kb = r.get("size", 0)
        if size_kb < 50 or size_kb > 6000:
            continue
        full = r["full_name"]
        fam_by_repo[full].add(fam)
        if full not in pool:
            pool[full] = {
                "full_name": full,
                "owner": r["owner"]["login"],
                "repo": r["name"],
                "size_kb": size_kb,
                "stars": r["stargazers_count"],
                "default_branch": r.get("default_branch","main"),
                "html_url": r["html_url"],
                "description": (r.get("description") or "")[:200],
                "language": r.get("language"),
            }

# Attach families
for full, r in pool.items():
    r["families_from_search"] = sorted(fam_by_repo[full])

# Dedupe by owner (within family) — pick 1 repo per owner for each family,
# the smallest one (size). We later need 8 distinct owners per (java × family).
out = sorted(pool.values(), key=lambda r: (-len(r["families_from_search"]), r["size_kb"]))

json.dump(out, open("attempt_2/discover/candidates.json","w"), indent=2)
print(f"total unique candidate repos: {len(out)}")
fam_counts = defaultdict(int)
for r in out:
    for f in r["families_from_search"]:
        fam_counts[f] += 1
for f,n in sorted(fam_counts.items()):
    print(f"  {f}: {n}")
# Distinct-owner sanity
owners = defaultdict(set)
for r in out:
    for f in r["families_from_search"]:
        owners[f].add(r["owner"])
print()
print("distinct owners per family:")
for f,o in sorted(owners.items()):
    print(f"  {f}: {len(o)}")
