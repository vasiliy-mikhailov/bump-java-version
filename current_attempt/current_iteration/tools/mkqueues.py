#!/usr/bin/env python3
"""mkqueues.py <baselines.jsonl> <outdir> -- build the per-hop roundrobin candidate queues.

Deterministic and reproducible: per hop (jv_from 8/11/17/21) take SIZES[h] repos from the corpus
baselines store, ordered by sha1(repo/sha) (a stable shuffle, so no year/discovery-order clustering).
The 2026-07-02 sweep's queues lived in /tmp and were lost to the reboot tmpfiles wipe; sizes here
reproduce that dataset's per-hop shape (204/154/578/636 = 1572). Queues + this builder now persist
under current_iteration so the dataset is regenerable byte-for-byte.
Line format (what roundrobin.sh reads): "<repo> <sha>".
"""
import hashlib
import json
import os
import sys

SIZES = {"8": 204, "11": 154, "17": 578, "21": 636}

src, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)

byhop = {h: [] for h in SIZES}
seen = set()
for line in open(src, errors="ignore"):
    try:
        r = json.loads(line)
    except Exception:
        continue
    h = str(r.get("jv_from"))
    repo, sha = r.get("repo"), r.get("sha")
    if h not in byhop or not repo or not sha or repo in seen:
        continue
    seen.add(repo)
    byhop[h].append((hashlib.sha1(f"{repo}/{sha}".encode()).hexdigest(), repo, sha))

total = 0
for h, rows in byhop.items():
    rows.sort()
    take = rows[:SIZES[h]]
    with open(os.path.join(outdir, f"cand_{h}.txt"), "w") as f:
        for _, repo, sha in take:
            f.write(f"{repo} {sha}\n")
    total += len(take)
    print(f"cand_{h}.txt: {len(take)} (of {len(rows)} available)")
print(f"total {total}")
