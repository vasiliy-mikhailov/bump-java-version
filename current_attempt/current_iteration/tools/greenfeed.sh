#!/bin/bash
# greenfeed.sh <green_jsonl> <runroot> [interval=60] -- queue feeder: follows the dig's green_baselines
# jsonl and appends "repo sha" to <runroot>/q/cand_<jv>.txt, FIRST row per repo wins (dedup ledger in
# q/.seen_repos). Append-only by construction, so the sweep's slug = line-index mapping stays stable.
set -uo pipefail
J=$1; RUN=$2; IV=${3:-60}
mkdir -p "$RUN/q"; SEEN="$RUN/q/.seen_repos"; touch "$SEEN"
while true; do
  [ -f "$J" ] && docker run --rm -i -v "$J:/j.jsonl:ro" python:3-slim python3 - <<'PY' | while read -r jv repo sha; do
import json
seen=set()
for l in open("/j.jsonl"):
    try: r=json.loads(l)
    except: continue
    if r["repo"] in seen: continue
    seen.add(r["repo"])
    print(r["jv_from"], r["repo"], r["sha"])
PY
    grep -qxF "$repo" "$SEEN" 2>/dev/null && continue
    echo "$repo $sha" >> "$RUN/q/cand_$jv.txt"
    echo "$repo" >> "$SEEN"
    echo "feed: $repo -> cand_$jv ($(date '+%H:%M'))"
  done
  sleep "$IV"
done
