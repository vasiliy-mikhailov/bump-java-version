"""Draw a fresh per-run iter-db by RANDOMLY sampling baselines from the outer corpus store.
<=1 sha per repo keeps datapoints repo-diverse. Paths from sibling _paths (override --store/--out).
Usage: python3 draw_iter.py [--store FILE] [--n 100] [--seed R] [--out FILE]"""
import json, random, sys
from collections import Counter
import _paths as P
NEXT = {8: 11, 11: 17, 17: 21, 21: 25}
def arg(n, d=None):
    for a in sys.argv:
        if a.startswith(n + "="):
            return a.split("=", 1)[1]
    return d
STORE = arg("--store", str(P.STORE))
N = int(arg("--n", "100")); SEED = int(arg("--seed", "0"))
OUT = arg("--out", str(P.DATASET))
baselines = []
for line in open(STORE):
    line = line.strip()
    if not line: continue
    try:
        b = json.loads(line)
        if b.get("jv_from") in NEXT: baselines.append(b)
    except Exception: pass
by_repo = {}
for b in baselines: by_repo.setdefault(b["repo"], []).append(b)
rng = random.Random(SEED); repos = list(by_repo); rng.shuffle(repos)
iter_db = []
for repo in repos[:N]:
    b = rng.choice(by_repo[repo])
    iter_db.append({"repo": b["repo"], "sha": b["sha"], "jv_from": b["jv_from"],
                    "jv_to": NEXT[b["jv_from"]], "year": b.get("year")})
json.dump(iter_db, open(OUT, "w"), indent=1)
hops = Counter(f"{e['jv_from']}->{e['jv_to']}" for e in iter_db)
print(f"iter: {len(iter_db)} datapoints (<=1/repo) from {len(by_repo)} repos / {len(baselines)} baselines; seed {SEED}; hops {dict(hops)} -> {OUT}")
