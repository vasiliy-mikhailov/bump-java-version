#!/bin/bash
# Wait for census -> union merge -> Java-8 dig (work-pool, star-sorted most-stars-first).
cd /home/vmihaylov/bump-java-version
LOG=/tmp/j8_pipe.log
echo "WAITING for census $(date -Is)" > $LOG
while [ ! -f /tmp/census_done.txt ]; do sleep 60; done
echo "CENSUS DONE $(cat /tmp/census_done.txt)" >> $LOG

echo "=== union merge (curated + census) ===" >> $LOG
python3 /tmp/merge_census.py >> $LOG 2>&1

echo "=== build Java-8 repo list (pool + candidates), STAR-SORTED ===" >> $LOG
python3 - >> $LOG 2>&1 <<'PY'
import json, subprocess
pool = json.load(open("current_attempt/dataset-repos.json"))
cand = [l.strip() for l in open("current_attempt/corpus/legacy/j8_candidates.txt") if l.strip()]
allr = list(dict.fromkeys(pool + cand))
stars = {}
B = 50
for i in range(0, len(allr), B):
    batch = allr[i:i + B]; parts = []
    for j, r in enumerate(batch):
        o, n = r.split("/", 1); o = o.replace("\\", "\\\\").replace('"', '\\"'); n = n.replace("\\", "\\\\").replace('"', '\\"')
        parts.append(f'a{j}: repository(owner: "{o}", name: "{n}") {{ stargazerCount }}')
    rr = subprocess.run(["gh", "api", "graphql", "-f", "query=query { " + " ".join(parts) + " }"], capture_output=True, text=True)
    try: data = json.loads(rr.stdout).get("data") or {}
    except Exception: data = {}
    for j, r in enumerate(batch):
        nd = data.get(f"a{j}"); stars[r] = nd["stargazerCount"] if nd and nd.get("stargazerCount") is not None else -1
allr.sort(key=lambda r: stars.get(r, -1), reverse=True)
json.dump(stars, open("current_attempt/corpus/legacy/j8_stars.json", "w"))
open("/tmp/j8_repos.txt", "w").write("\n".join(allr))
print("j8 dig repos:", len(allr), "| top:", [(r, stars[r]) for r in allr[:3]])
PY

docker run --rm --user root -v /tmp:/scratch --entrypoint sh j21-fitness:latest -c "rm -rf /scratch/samp_*" 2>/dev/null
rm -f current_attempt/corpus/legacy/j8dig.json current_attempt/corpus/legacy/j8dig.json.jsonl
date +%s > /tmp/j8dig.start
echo "J8DIG_LAUNCHED $(date -Is)" >> $LOG
python3 current_attempt/tools/sample_shas.py --only-from=8 --workers=6 --repos-file=/tmp/j8_repos.txt \
  --max-attempts=5 --scan-cap=70 --out=current_attempt/corpus/legacy/j8dig.json >> /tmp/j8dig.log 2>&1
echo "J8DIG_DONE elapsed=$(( $(date +%s) - $(cat /tmp/j8dig.start) ))s $(date -Is)" >> $LOG
