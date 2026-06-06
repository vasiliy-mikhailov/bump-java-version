#!/usr/bin/env python3
# Annotate each dataset baseline with its repo's GitHub stargazer count (batched GraphQL).
# Usage: python3 annotate_stars.py <dataset-shas.json>
import json, subprocess, sys
ds = sys.argv[1]
d = json.load(open(ds))
repos = sorted({e["repo"] for e in d})
stars = {}
B = 50
for i in range(0, len(repos), B):
    batch = repos[i:i + B]
    parts = []
    for j, r in enumerate(batch):
        owner, name = r.split("/", 1)
        owner = owner.replace("\\", "\\\\").replace('"', '\\"')
        name = name.replace("\\", "\\\\").replace('"', '\\"')
        parts.append(f'a{j}: repository(owner: "{owner}", name: "{name}") {{ stargazerCount }}')
    q = "query { " + " ".join(parts) + " }"
    r = subprocess.run(["gh", "api", "graphql", "-f", "query=" + q], capture_output=True, text=True)
    data = {}
    try:
        data = json.loads(r.stdout).get("data") or {}
    except Exception:
        data = {}
    for j, repo in enumerate(batch):
        node = data.get(f"a{j}")
        stars[repo] = node["stargazerCount"] if node and node.get("stargazerCount") is not None else -1
    print(f"  stars {min(i + B, len(repos))}/{len(repos)}", flush=True)
for e in d:
    e["stars"] = stars.get(e["repo"], -1)
json.dump(d, open(ds, "w"), indent=1)
ok = [e["stars"] for e in d if e["stars"] >= 0]
print(f"annotated {len(d)} baselines / {len(repos)} repos | stars min {min(ok) if ok else 0} "
      f"max {max(ok) if ok else 0} | unresolved {sum(1 for e in d if e['stars'] < 0)}")
