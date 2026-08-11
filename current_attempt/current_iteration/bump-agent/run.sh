#!/usr/bin/env bash
# Run a manifest of bumps through the chain, N at a time.
#
# THE LANE COUNT IS THE ONLY DIAL, AND IT IS LIVE. It is re-read from $LANEFILE before every
# launch, so a sweep that is starving the GPU can be throttled with `echo N > <runroot>/max_lanes`
# and one that is under-using it opened up, both without losing an in-flight bump. A multi-day
# sweep whose only throttle is a restart gets throttled by killing work.
#
# There is no per-bump budget: no step cap, no wall-clock, no token ceiling. A bump ends when the
# gate settles it or the chain files an honest verdict, and a hang is an infrastructure bug to fix
# at its root rather than something to bound here.
#
# Manifest is TSV: <slug> <owner/repo> <sha> <from> <to>. The from/to columns are the deterministic
# detector's guess; the surveyor may correct them, which is the point of having a surveyor.
#
#   LANES=4 ./run.sh /path/to/manifest.tsv
set -uo pipefail

MAN=${1:?usage: run.sh <manifest.tsv>}
LANES=${LANES:-3}
I=${BJV_ITER:-/home/vmihaylov/bump-java-version/current_attempt/current_iteration}
ROOT=${BJV_RUNROOT:-$I/runs_agent}
WS=$ROOT/ws
RESULTS=$ROOT/results
mkdir -p "$WS" "$RESULTS"

# The API key comes from the project .env, never from the manifest or the command line.
set -a; . "${BJV_ENV:-/home/vmihaylov/bump-java-version/.env}"; set +a
KEY=${OC_KEY:-${PROPOSER_API_KEY:-}}

settled() { # a slug is done when the settlements file holds a terminal state for it
  [ -f "$RESULTS/settlements.jsonl" ] || return 1
  grep -q "\"bump\":\"$1|" "$RESULTS/settlements.jsonl" 2>/dev/null &&
    grep "\"bump\":\"$1|" "$RESULTS/settlements.jsonl" | tail -1 |
    grep -qvE '"state":"bumping"'
}

one() {
  local slug=$1 repo=$2 sha=$3 from=$4 to=$5
  local w=$WS/$slug
  # THE CLAIM IS WHAT MAKES "IN FLIGHT" A FACT. A bump that dies leaves its last settlement row
  # reading "bumping" forever, which is indistinguishable from one still working. A claim file
  # that exists only while the lane does turns that into something a reader can check, and the
  # trap releases it however the lane ends, including a kill.
  local bslug
  bslug=$(printf '%s' "$repo|$sha|$from|$to" | sed 's/[^A-Za-z0-9]\+/_/g')
  mkdir -p "$RESULTS/claims" 2>/dev/null || docker run --rm -v "$RESULTS:/r" alpine mkdir -p /r/claims
  : > "$RESULTS/claims/$bslug" 2>/dev/null \
    || docker run --rm -v "$RESULTS:/r" alpine touch "/r/claims/$bslug"
  trap 'rm -f "$RESULTS/claims/$bslug" 2>/dev/null || docker run --rm -v "$RESULTS:/r" alpine rm -f "/r/claims/$bslug"' RETURN
  # A fresh checkout every time: the chain reads what each phase did back out of git diff, so a
  # workspace carrying a previous attempt's edits would attribute them to this run.
  docker run --rm -v "$WS:/w" alpine rm -rf "/w/$slug" >/dev/null 2>&1
  if ! git clone -q "https://github.com/$repo.git" "$w" 2>/dev/null; then
    echo "[$slug] clone failed: $repo"; return 1
  fi
  git -C "$w" checkout -q "$sha" 2>/dev/null || { echo "[$slug] no sha $sha"; return 1; }

  docker run --rm --name "bjvagent_$slug" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$I:$I" -v "$w:$w" \
    -v /home/vmihaylov/.m2-fitness:/home/vmihaylov/.m2-fitness \
    -v /home/vmihaylov/maven-config:/home/vmihaylov/maven-config:ro \
    -v /home/vmihaylov/.gradle-fitness:/home/vmihaylov/.gradle-fitness \
    -v /home/vmihaylov/.gradle-dists:/home/vmihaylov/.gradle-dists \
    -e OC_KEY="$KEY" -e OC_BASE="${OC_BASE:-}" -e OC_MODEL="${OC_MODEL:-}" \
    -e BJV_HOPTOOLS="$I/hoptools" -e BJV_PATIENCE_MINUTES="${BJV_PATIENCE_MINUTES:-45}" \
    bjv-agent "$w" "$repo|$sha|$from|$to" "$RESULTS" \
    >> "$ROOT/$slug.log" 2>&1
  rm -f "$RESULTS/claims/$bslug" 2>/dev/null \
    || docker run --rm -v "$RESULTS:/r" alpine rm -f "/r/claims/$bslug" >/dev/null 2>&1
  echo "[$slug] done: $(grep -c . "$ROOT/$slug.log" 2>/dev/null) log lines"
}

# The queue, where the dashboard can see it: it mounts $RESULTS and nothing else, and a page
# built only from settlements can never show the work that has not started yet.
cp "$MAN" "$RESULTS/queue.tsv"

LANEFILE=$ROOT/max_lanes
[ -f "$LANEFILE" ] || echo "$LANES" > "$LANEFILE"
lanes() { local n; n=$(cat "$LANEFILE" 2>/dev/null); case "$n" in ''|*[!0-9]*) echo "$LANES";; *) echo "$n";; esac; }

echo "manifest $MAN ($(grep -c . "$MAN") rows), $(lanes) lanes (live: $LANEFILE), results -> $RESULTS"
done_n=0; skipped=0
while read -r slug repo sha from to; do
  [ -z "${slug:-}" ] && continue
  if settled "$repo"; then skipped=$((skipped+1)); continue; fi
  while [ "$(jobs -rp | wc -l)" -ge "$(lanes)" ]; do wait -n 2>/dev/null || sleep 5; done
  one "$slug" "$repo" "$sha" "$from" "$to" &
  done_n=$((done_n+1))
done < "$MAN"
wait
echo "manifest complete: $done_n launched, $skipped already settled"
