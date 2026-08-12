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
mkdir -p "$WS" "$RESULTS" 2>/dev/null
# THE RESULTS TREE IS WRITTEN BY BOTH SIDES. The agent containers run as root and create their own
# directories there, so after the first lane the launcher can no longer write its own bookkeeping
# into it: the claims went from a fact to a permission error. Containers can write to a
# host-user-owned tree; the reverse is not true, so ownership goes one way once, here.
docker run --rm -v "$ROOT:/r" alpine chown -R "$(id -u):$(id -g)" /r/results >/dev/null 2>&1
mkdir -p "$RESULTS/claims"

# The API key comes from the project .env, never from the manifest or the command line.
set -a; . "${BJV_ENV:-/home/vmihaylov/bump-java-version/.env}"; set +a
KEY=${OC_KEY:-${PROPOSER_API_KEY:-}}

settled() { # a slug is done when the settlements file holds a terminal state for it
  [ -f "$RESULTS/settlements.jsonl" ] || return 1
  grep -q "\"bump\":\"$1|" "$RESULTS/settlements.jsonl" 2>/dev/null &&
    grep "\"bump\":\"$1|" "$RESULTS/settlements.jsonl" | tail -1 |
    grep -qvE '"state":"bumping"'
}

# A BUMP STILL IN FLIGHT IS NOT AN UNSETTLED BUMP. Its last settlement row reads "bumping", which
# settled() correctly calls unfinished -- so a relaunched sweep re-bit repos another lane was still
# working: 42 of 95 sessions were duplicates, and one() then rm -rf'd a workspace a live container
# was editing. The claim is the same fact the dashboard uses for "in flight", so ask it here too.
# THE CLAIM NAMES ITS OWN CONTAINER. Asking whether ANY bjvagent_ is running answers a different
# question: during a sweep one always is, so a claim left behind by a launcher that was killed
# stranded its repo for the rest of the run. The claim file now holds the container name, and a
# claim whose container is gone is stale by definition and gets cleared here.
inflight() {
  local claim="$RESULTS/claims/$1"
  [ -e "$claim" ] || return 1
  local name
  name=$(cat "$claim" 2>/dev/null)
  if [ -z "$name" ]; then
    # Written by an older build that recorded nothing; fall back to the weaker check it assumed.
    docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^bjvagent_" || { rm -f "$claim"; return 1; }
    return 0
  fi
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$name" || { rm -f "$claim"; return 1; }
  return 0
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
  echo "bjvagent_$slug" > "$RESULTS/claims/$bslug" 2>/dev/null || echo "[$slug] could not claim"
  trap 'rm -f "$RESULTS/claims/$bslug" 2>/dev/null' RETURN
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
  rm -f "$RESULTS/claims/$bslug" 2>/dev/null
  echo "[$slug] done: $(grep -c . "$ROOT/$slug.log" 2>/dev/null) log lines"
}

# ALPHABETICAL, ALWAYS. The order the manifest happens to be in is not a decision anyone made,
# and it decides which repos a partial sweep covers -- so two runs over the same corpus answer
# different questions. Sorting here means the order is a property of the corpus rather than of
# whoever last edited the file, and the dashboard shows the same order back.
# TWO LAUNCHERS SHARE THIS TREE, SO NOTHING HERE MAY BE WRITTEN TO A FIXED NAME. A one-row
# validation run used to sort into $ROOT/manifest.tsv, which is the file the full sweep was still
# reading from: the truncate landed under an open descriptor, the sweep read EOF, and a 1439-row
# run ended early and silently. The sorted copy is named after its input, so each launcher owns
# its own file and neither can shorten the other's.
SORTED=$ROOT/manifest.$(basename "$MAN" .tsv).sorted.tsv
# CASE-INSENSITIVE, BECAUSE THAT IS WHAT ALPHABETICAL MEANS TO A READER. A plain LC_ALL=C sort is
# ASCII, so every uppercase-initial repo precedes every lowercase one: aartiPl/tablevis sat at row
# 524 while the dashboard, which folds case, showed it 14th. Same corpus, two orders, and the sweep
# looked like it was skipping rows it had simply not reached. LC_ALL=C is kept for reproducibility
# and the fold is done by the key modifier, so the result is still locale-independent; the
# case-sensitive key second breaks ties the fold creates, exactly as the dashboard does.
LC_ALL=C sort -t "$(printf '\t')" -k2,2f -k2,2 -k4,4n "$MAN" > "$SORTED"
MAN=$SORTED

# The queue, where the dashboard can see it: it mounts $RESULTS and nothing else, and a page
# built only from settlements can never show the work that has not started yet. It accumulates
# rather than replaces, for the same reason: a small run is added to what is queued, not mistaken
# for the whole of it.
QUEUE=$RESULTS/queue.tsv
cat "$MAN" "$QUEUE" 2>/dev/null | awk 'NF && !seen[$2"\t"$4]++' |
  LC_ALL=C sort -t "$(printf '\t')" -k2,2 -k4,4n > "$QUEUE.new" && mv "$QUEUE.new" "$QUEUE"

LANEFILE=$ROOT/max_lanes
[ -f "$LANEFILE" ] || echo "$LANES" > "$LANEFILE"
lanes() { local n; n=$(cat "$LANEFILE" 2>/dev/null); case "$n" in ''|*[!0-9]*) echo "$LANES";; *) echo "$n";; esac; }

echo "manifest $MAN ($(grep -c . "$MAN") rows), $(lanes) lanes (live: $LANEFILE), results -> $RESULTS"
done_n=0; skipped=0
while read -r slug repo sha from to; do
  [ -z "${slug:-}" ] && continue
  if settled "$repo"; then skipped=$((skipped+1)); continue; fi
  bs=$(printf '%s' "$repo|$sha|$from|$to" | sed 's/[^A-Za-z0-9]\+/_/g')
  if inflight "$bs"; then echo "[$slug] already in flight, skipping"; skipped=$((skipped+1)); continue; fi
  while [ "$(jobs -rp | wc -l)" -ge "$(lanes)" ]; do wait -n 2>/dev/null || sleep 5; done
  one "$slug" "$repo" "$sha" "$from" "$to" &
  done_n=$((done_n+1))
done < "$MAN"
wait
echo "manifest complete: $done_n launched, $skipped already settled"
