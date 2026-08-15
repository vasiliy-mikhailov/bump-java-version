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
# Manifest is TSV: <slug> <group/project> <sha> <from> <to>. The from/to columns are the
# prescribed hop; the surveyor may disagree, which is recorded and does not change the hop.
#
#   GIT_BASE=https://gitlab.example.com LANES=4 ./run.sh /path/to/manifest.tsv
set -uo pipefail

MAN=${1:?usage: run.sh <manifest.tsv>}
LANES=${LANES:-3}
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -n "${BJV_ENV:-}" ] && [ -f "$BJV_ENV" ]; then
  set -a; . "$BJV_ENV"; set +a
elif [ -f "$HERE/.env" ]; then
  set -a; . "$HERE/.env"; set +a
fi
ROOT=${BJV_RUNROOT:-$HERE/runs_agent}
HOPTOOLS=${BJV_HOPTOOLS:-${BJV_ITER:+$BJV_ITER/hoptools}}
HOPTOOLS=${HOPTOOLS:-$(cd "$HERE/../hoptools" 2>/dev/null && pwd)}
: "${HOPTOOLS:?set BJV_HOPTOOLS to the host path of hoptools/}"
AGENT_IMAGE=${BJV_IMAGE:-bjv}
WS=$ROOT/ws
RESULTS=$ROOT/results
mkdir -p "$WS" "$RESULTS" 2>/dev/null
# THE RESULTS TREE IS WRITTEN BY BOTH SIDES. The agent containers run as root and create their own
# directories there, so after the first lane the launcher can no longer write its own bookkeeping
# into it: the claims went from a fact to a permission error. Containers can write to a
# host-user-owned tree; the reverse is not true, so ownership goes one way once, here.
docker run --rm -v "$ROOT:/r" alpine chown -R "$(id -u):$(id -g)" /r/results >/dev/null 2>&1
mkdir -p "$RESULTS/claims"

# The API key comes from the environment / .env, never from the manifest or the command line.
KEY=${OC_KEY:-${PROPOSER_API_KEY:-}}
: "${GIT_BASE:?set GIT_BASE (e.g. https://gitlab.example.com)}"

# A LANE WITH NO ENDPOINT DIES IN SECONDS AND SAYS SO ONLY IN ITS OWN LOG.
#
# Model requires OC_BASE and OC_MODEL now rather than defaulting to one machine's inference host,
# and that is the right change: the default was a pin to the author's box, and it had gone stale
# besides, naming a different model from the one this host's .env configures. What it introduced is
# a silent failure at the other end. This loop passes those two through only when they are set, so
# an unset pair does not stop anything: it launches lane after lane, each of which starts, throws
# IllegalStateException and exits. Thirty-five bumps went past that way in under a minute, each
# leaving a "bumping" heartbeat and no verdict, before anyone opened a log.
#
# One refusal here, before the first lane, costs one line and reads as what it is.
: "${OC_BASE:?set OC_BASE (bump-agent/.env, or the environment): a lane cannot start without one}"
: "${OC_MODEL:?set OC_MODEL (bump-agent/.env, or the environment)}"

# AND THE SANDBOX MOUNTS, WHICH FAIL WORSE THAN THE ENDPOINT DOES.
#
# A lane with no endpoint dies loudly enough to notice. A lane with no dependency cache runs to
# completion and produces verdicts. Runner.env copies these only when set and jvm-run mounts each
# only when it exists, so unset means the build container comes up with an empty /root/.m2 and no
# settings.xml, and against an offline Nexus nothing resolves. The corpus then fills with results
# that look ordinary: a project settles no-baseline for "does not build" when it builds fine, and
# a scan reports CRITICAL+HIGH 0 -> 0 because trivy was handed an empty directory. One repo did
# both within an hour of PASSing on the same commit.
#
# So this refuses rather than warns: a warning is what scrolled past while 112GB of cache sat
# unmounted. BJV_ALLOW_NO_CACHE=1 is the way out for an install that really has no warm cache and
# expects to resolve from the network.
if [ -z "${BJV_ALLOW_NO_CACHE:-}" ]; then
  [ -d "${BJV_M2:-}" ] || { echo "run.sh: BJV_M2 is not a directory (${BJV_M2:-unset}). An offline sweep resolves nothing without it, and the verdicts it produces will look ordinary and be wrong. Set it, or set BJV_ALLOW_NO_CACHE=1 if that is genuinely what you want." >&2; exit 1; }
  [ -e "${BJV_SETTINGS:-}" ] || { echo "run.sh: BJV_SETTINGS is not a file (${BJV_SETTINGS:-unset}). Without the mirror settings maven will not reach the local Nexus. Set it, or set BJV_ALLOW_NO_CACHE=1." >&2; exit 1; }
fi

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

# A BUMP THE SUPERVISOR SET ASIDE. Not a verdict and not a skip: it keeps whatever it had, and it
# comes back the moment there is nothing else to run. The marker is a file rather than a state so
# that a supervisor writing one and a launcher reading it need agree on nothing else.
postponed() {
  [ -e "$RESULTS/postponed/$1" ]
}

# Takes the RESOLVED url, because a manifest row may name its own host and its own token
# and the default-branch lookup already needed the url before this runs. What belongs
# here is the credential choice: an ssh key, a bearer token, or neither.
clone_repo() {
  local url=$1 dest=$2
  if [ -n "${GIT_SSH_KEY:-}" ]; then
    GIT_SSH_COMMAND="ssh -i ${GIT_SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
      git clone -q "$url" "$dest"
  elif [ -n "${GIT_TOKEN:-}" ]; then
    git -c http.extraHeader="Authorization: Bearer ${GIT_TOKEN}" clone -q "$url" "$dest"
  else
    git clone -q "$url" "$dest"
  fi
}

one() {
  local slug=$1 repo=$2 sha=$3 from=$4 to=$5
  local w=$WS/$slug

  # WHERE FROM, AND WITH WHOSE CREDENTIAL. An uploaded registry carries a URL, because not every
  # repository is on github.com, and may carry a token for a private one. Both live beside the
  # manifest rather than in it: the manifest is keyed by owner/name because the whole record is,
  # and $RESULTS is what the dashboard serves, so a token in there would be one careless endpoint
  # away from being published.
  local origin token
  origin=$(awk -F'\t' -v r="$repo" '$1==r{print $2; exit}' "$ROOT/origins.tsv" 2>/dev/null)
  [ -n "${origin:-}" ] || origin="${GIT_BASE%/}/$repo.git"
  token=$(awk -F'\t' -v r="$repo" '$1==r{print $2; exit}' "$ROOT/credentials.tsv" 2>/dev/null)
  if [ -n "${token:-}" ]; then
    # In the URL rather than a helper, so it never lands in a config file inside the workspace that
    # a later `git diff` or an agent's read_file could surface.
    origin=$(printf '%s' "$origin" | sed "s#://#://x-access-token:$token@#")
  fi

  # A BLANK SHA MEANS THE DEFAULT BRANCH, RESOLVED BEFORE ANYTHING IS KEYED BY IT. The claim, the
  # results directory and the bump key are all derived from the sha below, so resolving it after
  # the clone would file the work under "-" and then run it under a commit — one bump wearing two
  # identities. ls-remote answers without a clone, which is why this can happen up here.
  if [ "$sha" = "-" ] || [ -z "${sha:-}" ]; then
    sha=$(git ls-remote "$origin" HEAD 2>/dev/null | awk '{print $1; exit}')
    if [ -z "${sha:-}" ]; then echo "[$slug] cannot resolve default branch: $repo"; return 1; fi
    echo "[$slug] unpinned; resolved $(printf '%.12s' "$sha")"
  fi

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
  # Never echo $origin on failure: it may carry a token.
  if ! clone_repo "$origin" "$w" 2>/dev/null; then
    echo "[$slug] clone failed: $repo"; return 1
  fi
  git -C "$w" checkout -q "$sha" 2>/dev/null || { echo "[$slug] no sha $sha"; return 1; }

  local vols=(-v /var/run/docker.sock:/var/run/docker.sock -v "$w:$w" -v "$RESULTS:$RESULTS"
              -v "$HOPTOOLS:$HOPTOOLS:ro")
  [ -n "${BJV_ITER:-}" ] && [ -d "$BJV_ITER" ] && vols+=(-v "$BJV_ITER:$BJV_ITER")
  [ -d "${BJV_M2:-}" ] && vols+=(-v "$BJV_M2:$BJV_M2")
  [ -e "${BJV_SETTINGS:-}" ] && vols+=(-v "$BJV_SETTINGS:$BJV_SETTINGS:ro")
  [ -d "${BJV_GRADLE_RO:-}" ] && vols+=(-v "$BJV_GRADLE_RO:$BJV_GRADLE_RO:ro")
  [ -d "${BJV_GRADLE_DISTS:-}" ] && vols+=(-v "$BJV_GRADLE_DISTS:$BJV_GRADLE_DISTS")
  [ -e "${BJV_GRADLE_INIT:-}" ] && vols+=(-v "$BJV_GRADLE_INIT:$BJV_GRADLE_INIT:ro")

  local envs=(-e "OC_KEY=$KEY" -e "BJV_HOPTOOLS=$HOPTOOLS"
              -e "BJV_PATIENCE_MINUTES=${BJV_PATIENCE_MINUTES:-45}")
  for v in OC_BASE OC_MODEL BJV_REPO_URL BJV_NET BJV_M2 BJV_SETTINGS BJV_GRADLE_RO BJV_GRADLE_DISTS \
           BJV_GRADLE_INIT BJV_JDK_IMAGE BJV_SCAN_IMAGE BJV_THINKING BJV_HANG_GUARD BJV_BUILD_SECONDS; do
    [ -n "${!v:-}" ] && envs+=(-e "$v=${!v}")
  done

  # THE LANE WATCHES ITSELF, so nothing outside needs the docker socket to stop it. A heartbeat on
  # the claim is what makes "running" observable to a reader that cannot ask the daemon, and the
  # postpone marker is honoured here because this is the one place that already holds the container
  # name and already has docker. A watcher that had to kill lanes needed the socket; a watcher that
  # only writes a marker does not, which is what lets it share a container with a page on the
  # public internet.
  ( while docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^bjvagent_$slug$"; do
      touch "$RESULTS/claims/$bslug" 2>/dev/null
      if [ -e "$RESULTS/postponed/$bslug" ]; then
        echo "[$slug] postponed: $(cut -c1-90 < "$RESULTS/postponed/$bslug" 2>/dev/null)"
        docker rm -f "bjvagent_$slug" >/dev/null 2>&1
        break
      fi
      sleep 30
    done ) &
  local watchdog=$!
  trap 'kill '"$watchdog"' 2>/dev/null; rm -f "$RESULTS/claims/'"$bslug"'" 2>/dev/null' RETURN

  docker run --rm --name "bjvagent_$slug" \
    "${vols[@]}" "${envs[@]}" \
    "$AGENT_IMAGE" tech.mikhailov.bjv.agent.Bump "$w" "$repo|$sha|$from|$to" "$RESULTS" \
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

# WHICH MANIFEST THIS SWEEP IS ACTUALLY READING, written down where the dashboard can find it.
# The sorted copy is named after its input so two launchers cannot shorten each other's file, which
# means the name is not predictable from outside. Uploading a registry has to merge into THIS file
# to join THIS sweep, and guessing which of the manifest.*.sorted.tsv is live would eventually pick
# the wrong one. The loop below re-reads $MAN every round, so a merge lands on the next one.
# THE BASENAME, NOT THE PATH. The dashboard reads this from inside a container where the run root
# is mounted somewhere else entirely, so a host path recorded here does not resolve there and the
# upload silently falls back to a file no sweep is reading.
basename "$MAN" > "$ROOT/active_manifest"
trap 'rm -f "$ROOT/active_manifest" 2>/dev/null' EXIT

# The queue, where the dashboard can see it: it mounts $RESULTS and nothing else, and a page
# built only from settlements can never show the work that has not started yet. It accumulates
# rather than replaces, for the same reason: a small run is added to what is queued, not mistaken
# for the whole of it.
QUEUE=$RESULTS/queue.tsv
# THE MISSING FILE MUST NOT POISON THE PIPELINE. This was `cat "$MAN" "$QUEUE" 2>/dev/null | ...`,
# and with `set -o pipefail` a first run has no $QUEUE, so cat exits non-zero, the pipeline does
# too, the `&&` never fires and the queue is never written. Silently, because there is no `set -e`.
# The dashboard was promised this file by the comment above and never got it on a clean start: a
# sweep of 1439 rows showed the four a lane had already picked up.
{ cat "$MAN"; if [ -f "$QUEUE" ]; then cat "$QUEUE"; fi; } | awk 'NF && !seen[$2"\t"$4]++' |
  LC_ALL=C sort -t "$(printf '\t')" -k2,2 -k4,4n > "$QUEUE.new" && mv "$QUEUE.new" "$QUEUE"
[ -s "$QUEUE" ] || echo "WARNING: the queue is empty; the dashboard will show only started work"

LANEFILE=$ROOT/max_lanes
[ -f "$LANEFILE" ] || echo "$LANES" > "$LANEFILE"
lanes() { local n; n=$(cat "$LANEFILE" 2>/dev/null); case "$n" in ''|*[!0-9]*) echo "$LANES";; *) echo "$n";; esac; }

echo "manifest $MAN ($(grep -c . "$MAN") rows), $(lanes) lanes (live: $LANEFILE), results -> $RESULTS"
rounds=0
while :; do
done_n=0; skipped=0; postponed_n=0
while read -r slug repo sha from to; do
  [ -z "${slug:-}" ] && continue
  if settled "$repo"; then skipped=$((skipped+1)); continue; fi
  bs=$(printf '%s' "$repo|$sha|$from|$to" | sed 's/[^A-Za-z0-9]\+/_/g')
  if inflight "$bs"; then echo "[$slug] already in flight, skipping"; skipped=$((skipped+1)); continue; fi
  # The slug the supervisor knows is the results directory's, which is the bump slug, not $slug.
  if postponed "$bs"; then postponed_n=$((postponed_n+1)); continue; fi
  # POLLED, NOT PARKED ON A COMPLETION. lanes() re-reads max_lanes on every evaluation, but
  #  returns only when a lane EXITS, so the file was re-read at exactly the moments its
  # value could not matter. Raising the limit from the dashboard then did nothing until something
  # finished: saved as 6, still running 4, with no way to tell the difference from a broken write.
  # Two seconds of latency reusing a freed slot is nothing against a bump that runs for an hour.
  while [ "$(jobs -rp | wc -l)" -ge "$(lanes)" ]; do sleep 2; done
  one "$slug" "$repo" "$sha" "$from" "$to" &
  done_n=$((done_n+1))
done < "$MAN"
wait
echo "manifest complete: $done_n launched, $skipped already settled, $postponed_n postponed"

# NOTHING LEFT BUT THE ONES THAT WERE SET ASIDE. A postponement frees a slot for work that can
# progress; it is not a way to drop a repo from the corpus. Once the queue holds nothing else, the
# reason to keep them aside is gone and they are the work. Bounded, because a bump that is postponed
# again the moment it starts would otherwise loop here forever.
  rounds=$((rounds+1))
  # ONLY WHEN THEY ARE ALL THAT IS LEFT. A postponement frees a slot for work that can progress, so
  # it must outlast every round that still had such work: clearing it after a pass that launched 500
  # repos hands the slot straight back to the lane that was going nowhere. The smoke test caught
  # this on its first run -- a pass that skipped one bump and launched another cleared the marker
  # regardless, which made postponing mean nothing at all.
  if [ "$done_n" -gt 0 ] || [ "$postponed_n" -eq 0 ] || [ "$rounds" -ge 3 ]; then
    [ "$postponed_n" -gt 0 ] && echo "$postponed_n left postponed; they run when nothing else is waiting"
    break
  fi
  echo "only postponed bumps remain ($postponed_n); clearing them and going again"
  # THE MARKERS ARE ROOT-OWNED. The supervisor writes them from inside a container, so the launcher
  # -- a host user -- cannot remove them, and this clearing silently did nothing at all. The same
  # asymmetry the results tree already handles once at startup, and the smoke test could not catch
  # it because its own markers were host-written.
  docker run --rm -v "$RESULTS/postponed:/p" alpine sh -c 'rm -f /p/*' >/dev/null 2>&1 \
    || rm -f "$RESULTS/postponed"/* 2>/dev/null
done
