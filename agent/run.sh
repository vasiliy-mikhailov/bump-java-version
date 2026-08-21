#!/usr/bin/env bash
# Run a manifest of bumps through the chain, N at a time.
#
# THE LANE COUNT IS THE ONLY DIAL, AND IT IS LIVE. It is re-read from $LANEFILE before every
# launch, so a sweep that is starving the GPU can be throttled with `echo N > <runroot>/max_lanes`
# and one that is under-using it opened up, both without losing an in-flight bump. A multi-day
# sweep whose only throttle is a restart gets throttled by killing work.
#
# A LANE HAS A ROUND, AND A ROUND IS NOT A CAP ON THE WORK. There is still no step cap, no token
# ceiling and nothing an agent can see. What there is now is a wall-clock budget on a LANE: when it
# runs out the bump stops between stages, keeps its checkout and its journal, and goes back to the
# queue with its round incremented, so the next lane continues from where this one stopped. The
# written finding this project keeps is that limits told to a model produce garbage and give-up;
# nothing here is told to a model. See round_seconds() and the watchdog inside one().
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
ROOT=${BJV_RUNROOT:-$(cd "$HERE/.." && pwd)/runs/agent}
HOPTOOLS=${BJV_HOPTOOLS:-${BJV_ITER:+$BJV_ITER/hoptools}}
HOPTOOLS=${HOPTOOLS:-$(cd "$HERE/../hoptools" 2>/dev/null && pwd)}
: "${HOPTOOLS:?set BJV_HOPTOOLS to the host path of hoptools/}"
AGENT_IMAGE=${BJV_IMAGE:-bjv}
# WHAT THE TAG RESOLVED TO WHEN THIS LANE STARTED. bjv moves on every deploy and a running lane
# keeps the image it began with, so the tag is not an answer to what produced a result. Resolved
# once here and passed in, because the container cannot ask.
BJV_IMAGE_ID=$(docker image inspect -f '{{.Id}}' "$AGENT_IMAGE" 2>/dev/null || echo "")
export BJV_IMAGE_ID
WS=$ROOT/ws
RESULTS=$ROOT/results
mkdir -p "$WS" "$RESULTS" 2>/dev/null
# THE RESULTS TREE IS WRITTEN BY BOTH SIDES. The agent containers run as root and create their own
# directories there, so after the first lane the launcher can no longer write its own bookkeeping
# into it: the claims went from a fact to a permission error. Containers can write to a
# host-user-owned tree; the reverse is not true, so ownership goes one way once, here.
docker run --rm -v "$ROOT:/r" alpine chown -R "$(id -u):$(id -g)" /r/results >/dev/null 2>&1
mkdir -p "$RESULTS/claims"
# THE TWO ROUND DIRECTORIES, CREATED BY THE LAUNCHER SO THE LAUNCHER CAN UNLINK FROM THEM. The
# containers run as root and the postponed markers taught this lesson once already: removing a file
# needs write permission on its DIRECTORY, so a directory this host user made stays one it can
# empty however the file inside it got there.
mkdir -p "$RESULTS/expiring" "$RESULTS/rounds"

# WHAT THE SETTINGS PAGE SAVED, READ ONCE PER LANE AND NOT ONCE PER LAUNCHER.
#
# The key, the endpoint and the model name used to be read here, at startup, and stamped into every
# lane this launcher would ever open. That is the same staleness the dashboard was built to remove:
# the key was rotated in all three env files an hour before this was written, the page picked it up
# on deploy, and every lane in flight carried on with the old one for the rest of the day. Worse,
# the page said "what is saved here is what the next launch reads" while nothing read the file it
# wrote at all.
#
# So the readers below are called from inside one(), which runs in its own subshell per bump. One
# sed per lane costs nothing against a bump that runs for an hour, and it makes the effect boundary
# the next LANE rather than the next launcher.
#
# PARSED, NEVER SOURCED. This file is written by a page on the public internet. `.` would make a
# saved value a command this launcher runs as the host user.
#
# BLANK IS NOT A VALUE, AND GETTING THAT BACKWARDS IS THE OUTAGE THIS IS MEANT TO PREVENT. A missing
# file, a directory, and a file the mode forbids all yield the empty string here, so every reader
# checks for a non-empty answer before it stops looking. An empty answer allowed to win over $OC_KEY
# is a whole sweep launched with no credentials, which has happened once already.
SETTINGS=${BJV_MODEL_SETTINGS:-$ROOT/model}
LEGACY_KEY=$ROOT/model_key
readable() { [ -f "$SETTINGS" ] && [ -r "$SETTINGS" ]; }
saved() { readable || return 0; sed -n "s/^$1=//p" "$SETTINGS" 2>/dev/null | tail -1 | tr -d '\r'; }
# The page wins and the environment is underneath, which is the precedence the page's prose
# describes and the Java's ModelSettings implements. model_key is what the page wrote before the
# store existed: read, never written.
model_key_now() {
  local v; v=$(saved key)
  # THE GUARD IS THE SHELL'S, NOT tr'S. A redirection is performed by the shell before the
  # command runs, so `2>/dev/null` on tr never sees "no such file": that message is the
  # shell's and it reached the log on every lane start until this test went in front of it.
  [ -n "$v" ] || { [ -f "$LEGACY_KEY" ] && v=$(tr -d '\r\n' < "$LEGACY_KEY" 2>/dev/null); }
  [ -n "$v" ] || v=${OC_KEY:-${PROPOSER_API_KEY:-}}
  printf '%s' "$v"
}
model_base_now() { local v; v=$(saved endpoint); [ -n "$v" ] || v=${OC_BASE:-}; printf '%s' "$v"; }
model_name_now() { local v; v=$(saved model); [ -n "$v" ] || v=${OC_MODEL:-}; printf '%s' "$v"; }
# WHICH SETTINGS A LANE WAS STARTED WITH, so the page can tell "saved" from "in force". The mtime of
# the file that was read, and 0 when there was nothing readable to read: never the key, never a
# digest of one. A store that exists and cannot be read records 0 on purpose, so the page says a
# lane has not picked it up rather than claiming it has.
settings_stamp() { readable && stat -c %Y "$SETTINGS" 2>/dev/null || echo 0; }
if [ -e "$SETTINGS" ] && ! readable; then
  echo "run.sh: $SETTINGS exists and cannot be read; every lane will use the environment instead" >&2
fi
: "${GIT_BASE:?set GIT_BASE (e.g. https://gitlab.example.com)}"

# A LANE WITH NO ENDPOINT DIES IN SECONDS AND SAYS SO ONLY IN ITS OWN LOG.
#
# Model requires a base URL and a model name rather than defaulting to one machine's inference host,
# and that is the right change: the default was a pin to the author's box, and it had gone stale
# besides, naming a different model from the one this host's .env configures. What it introduced is
# a silent failure at the other end. Unset, nothing stops: lane after lane starts, throws
# IllegalStateException and exits. Thirty-five bumps went past that way in under a minute, each
# leaving a "bumping" heartbeat and no verdict, before anyone opened a log.
#
# One refusal here, before the first lane, costs one line and reads as what it is. It asks the same
# readers a lane will, so a value saved on the settings page satisfies it and an environment with
# neither does not.
[ -n "$(model_base_now)" ] || { echo "run.sh: no endpoint, on the settings page or in the environment (OC_BASE): a lane cannot start without one" >&2; exit 1; }
[ -n "$(model_name_now)" ] || { echo "run.sh: no model, on the settings page or in the environment (OC_MODEL)" >&2; exit 1; }

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
    grep -qvE '"state":"(bumping|requeued|paused)"'
}

# ---------------------------------------------------------------- rounds
#
# A ROUND IS ONE LANE'S WALL CLOCK, AND `paused` IS WHAT A BUMP LOOKS LIKE BETWEEN TWO OF THEM.
#
# WHY A NEW WORD AND NOT `requeued`. requeued is somebody on a page asking for the work to be done
# again FROM THE START, which is the opposite instruction about the stored state: resuming one would
# hand that person back exactly what they were trying to discard. Two opposite meanings cannot share
# one word, so the boundary gets its own and settled() above learns it.
#
# WHY THAT IS SAFE AGAINST THE LAUNCHER THAT IS RUNNING RIGHT NOW, which reads a script by byte
# offset and cannot be corrected: an old launcher's settled() would call `paused` terminal and drop
# the bump. It never sees one. The clock lives entirely in this file -- the container has none and
# reacts only to a marker THIS file writes -- so an old launcher expires nothing and the image under
# it never writes the word. Keep it that way. The moment the container is given a budget of its own,
# deploying the image before restarting the launcher silently marks bumps terminal.
#
# THE MEASUREMENT BEHIND THE DEFAULT. Over the 178 terminal bumps of the live sweep, which cost 733
# lane-hours between them, a six-hour budget ends 13% of bumps and only 7% of the PASSes at a
# boundary, and the 13% it does end are holding 278 of those 733 lane-hours: 38% of the sweep's GPU
# time sits in the tail of one bump in eight, and 15 of those 24 produce no PASS at all today.
# Dropping to 240 would cut 22 PASSes -- 17% of every success this corpus has -- to reclaim 64 more
# lane-hours, which is buying GPU with results; raising to 600 gives back 70 lane-hours and lets one
# repository hold a lane for most of a night before anything else can use it.
#
# ELAPSED, NOT "WORKING", AND THAT IS MEASURED RATHER THAN PREFERRED. The working-time column that
# argued for a smaller budget is an artefact of computing it over settlements.jsonl, which carries
# about seventeen progress rows per bump. Recomputed over trace.jsonl: of the 935 settlement
# silences longer than twenty minutes in this corpus, 932 have the trace working continuously
# through them, with an internal gap of a couple of minutes. There is no idleness to subtract, so
# elapsed IS working here and the watchdog's only observable is the right one.
#
# CALIBRATED AT EIGHT LANES ON THE NVFP4 ENDPOINT. Elapsed moves with contention, so raising
# max_lanes without raising this raises the round rate silently. Read live, per lane, exactly as
# max_lanes is, so the correction does not cost a restart.
ROUNDFILE=$ROOT/round_minutes
round_seconds() {
  local n
  n=$(cat "$ROUNDFILE" 2>/dev/null)
  case "$n" in ''|*[!0-9]*) n=${BJV_ROUND_MINUTES:-360} ;; esac
  # Seconds are the test's seam and nobody else's: a scenario that had to wait six hours would not
  # be a scenario. Whole minutes are what a person sets.
  echo "${BJV_ROUND_SECONDS:-$(( n * 60 ))}"
}
# HOW LONG AFTER EXPIRY THE CONTAINER IS KILLED OUTRIGHT. The marker is read between stages, and a
# stage is bounded by a build (BJV_BUILD_SECONDS, 1900s) or by one model call
# (BJV_PATIENCE_MINUTES, 45), so a lane that has not stopped an hour later is not going to.
ROUND_GRACE_S=${BJV_ROUND_GRACE_SECONDS:-$(( ${BJV_ROUND_GRACE:-45} * 60 ))}
# HOW MANY ROUNDS ONE BUMP MAY HAVE. Without a cap the launcher cannot terminate: a pathological
# repository would be re-offered for ever. Four rounds is 24 lane-hours, which only five or six
# bumps in 178 have ever needed, and the two that most wanted it were an infra bump at 55 hours and
# a blocked-dependency at 41. 0 means no cap, for whoever wants that on their own head.
MAX_ROUNDS=${BJV_MAX_ROUNDS:-4}
# BEFORE A PAUSED BUMP IS OFFERED AGAIN. One whole budget, so other work gets first refusal for a
# round's worth of time and the workspace stays warm for hours rather than weeks. Expressed as the
# instant a marker must predate, because find -mmin is whole minutes and a scenario's round is
# seconds long.
round_due_before() { echo "@$(( $(date +%s) - $(round_seconds) ))"; }
# FREE SPACE BELOW WHICH A PAUSED WORKSPACE IS NOT KEPT. Wiping is always safe and keeping is the
# risky choice, so every doubt falls towards wipe and costs time rather than correctness.
WS_FLOOR_GB=${BJV_WS_FLOOR_GB:-60}
# A PAUSED WORKSPACE NOBODY CAME BACK FOR. Kept warm, not kept for ever.
ROUND_KEEP_HOURS=${BJV_ROUND_KEEP_HOURS:-48}

# The newest row about EXACTLY this bump. The trailing comma is the anchor: `owner/repo|abc|8|17`
# is a prefix of nothing else in that file, but a key without the comma would be a prefix of a
# longer sha, and the whole sweep shares the file.
lastrow() {
  [ -f "$RESULTS/settlements.jsonl" ] || return 0
  grep -F "\"bump\":\"$1\"," "$RESULTS/settlements.jsonl" 2>/dev/null | tail -1
}
last_state() { lastrow "$1" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p'; }
# ZERO WHEN THERE IS NO ANSWER, and never the empty string: this is read straight into arithmetic,
# and `$(( now -  ))` is a syntax error whose failure the age test would then read as "young
# enough to keep". A doubt about a workspace has to fall towards wiping it.
last_at() {
  local n; n=$(lastrow "$1" | sed -n 's/^{"at":"\([0-9]*\)".*/\1/p')
  case "$n" in ''|*[!0-9]*) echo 0 ;; *) echo "$n" ;; esac
}

# WHICH PIPELINE THE LAST LANE PUT ITS NAME TO, lifted out of its own row VERBATIM rather than
# recomputed. The four fields are prompts and bill-of-materials hashes over a store this shell
# cannot read and a build stamp inside an image it cannot open, so recomputing them here is not an
# option; copying them is, and a copy cannot disagree with the original. They are written as one
# string by Version.fields, so one match takes all four or none.
provenance_of() {
  lastrow "$1" | grep -o '"commit":"[^"]*","image":"[^"]*","prompts":"[^"]*","boms":"[^"]*"'
}

# HOW MANY ROUNDS THIS BUMP HAS ALREADY ENDED, counted rather than kept. A stored counter would be
# a second copy of a fact these rows already carry, and two copies drift; the rows are the fact.
rounds_done() {
  [ -f "$RESULTS/settlements.jsonl" ] || { echo 0; return; }
  grep -F "\"bump\":\"$1\"," "$RESULTS/settlements.jsonl" 2>/dev/null |
    grep -c '"state":"paused"'
}

# ONE ROW, APPENDED THE WAY THE JAVA APPENDS ONE. The field order is Settlement.write's, character
# for character, because a reader parses this file with one parser.
#
# baseline AND gate ARE false ON THIS ROW BECAUSE NOTHING HERE KNOWS BETTER, and there is no better
# source: a lane that had to be killed left only progress notes, and every progress note writes both
# as false. The graceful boundary is the Java's and carries the real pair. The cost is that a
# hard-killed round shows two unreached lamps on the page, which understates it and never overstates
# it, and the next round rewrites them.
#
# THE SHELL WRITES A BOUNDARY ROW ONLY WHEN THE JAVA DID NOT. The graceful path is the Java's: it is
# the only side that can compute the pipeline fields, and it stops at a stage edge so nothing is
# lost. This is the backstop for a lane that was wedged and had to be killed, and it is the reason
# the budget bounds a HANG rather than only a slow bump. Without it a killed lane's last row would
# read "bumping" for ever and the round would never increment.
settle_row() {                     # settle_row <bump-key> <state> <because> <round>
  local key=$1 state=$2 why=$3 n=$4 prov row
  prov=$(provenance_of "$key")
  # THE ROUND IS APPENDED LAST AND OUTSIDE THE FINGERPRINT, exactly as the Java appends it: that
  # string is what the next lane's resume decision compares, so a round number inside it would make
  # every round look like a new pipeline and nothing would ever continue.
  row=$(printf '{"at":"%s","bump":"%s","state":"%s","because":"%s","baseline":false,"gate":false,"resumed":false%s%s}' \
        "$(date +%s%3N)" "$key" "$state" "$why" "${prov:+,$prov}" ",\"round\":\"$n\"")
  # THE FILE MAY BE ROOT-OWNED, because a container created it. The same asymmetry the results tree
  # is chowned for once at startup, and the postponed markers had to learn the hard way.
  printf '%s\n' "$row" >> "$RESULTS/settlements.jsonl" 2>/dev/null ||
    printf '%s\n' "$row" |
      docker run --rm -i -v "$RESULTS:/r" alpine sh -c 'cat >> /r/settlements.jsonl' >/dev/null 2>&1
}

# Free gigabytes where the workspaces live. BJV_FREE_GB is the test's seam: df is the one outside
# call here that is neither docker nor git, so a scenario cannot otherwise reach the floor.
free_gb() {
  local n
  if [ -n "${BJV_FREE_GB:-}" ]; then echo "$BJV_FREE_GB"; return; fi
  n=$(df -Pk "$WS" 2>/dev/null | awk 'NR==2{print int($4/1048576)}')
  case "$n" in ''|*[!0-9]*) echo 999999 ;; *) echo "$n" ;; esac
}

# The workspace, removed through a container because part of it is root-owned build output.
wipe_ws() {
  docker run --rm -v "$WS:/w" alpine rm -rf "/w/$1" >/dev/null 2>&1 || rm -rf "${WS:?}/$1" 2>/dev/null
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
    # BASIC, NOT BEARER, AND THAT IS NOT A PREFERENCE. GitHub accepts a personal access token as a
    # Bearer credential for git over HTTP; GitLab does not, and refuses the clone outright. Measured
    # against the local mirror: Bearer FAILS, Basic with oauth2 as the username succeeds, both on
    # the internal address and through the public name.
    #
    # Basic works for BOTH hosts, since GitHub accepts the token as the password with any username,
    # so this is one form rather than a fork on which host the row happens to name.
    #
    # The header rather than credentials in the url: a url carrying a token turns up in git error
    # text, in ps, and in any log that echoes what it tried to clone.
    git -c http.extraHeader="Authorization: Basic $(printf 'oauth2:%s' "${GIT_TOKEN}" | base64 | tr -d '\n')" \
      clone -q "$url" "$dest"
  else
    git clone -q "$url" "$dest"
  fi
}

one() {
  local slug=$1 repo=$2 sha=$3 from=$4 to=$5
  local w=$WS/$slug

  # WHAT THIS LANE IS TOLD TO CALL, READ NOW RATHER THAN WHEN THE SWEEP BEGAN. See the readers at
  # the top: the settings page wins, the environment is underneath, and none of the three is ever
  # echoed, put in the banner, or left anywhere `set -x` would reach.
  local key base name
  key=$(model_key_now); base=$(model_base_now); name=$(model_name_now)
  if [ -z "$key" ]; then
    # A LANE WITH NO KEY DOES NOT DIE, WHICH IS WHY THIS REFUSES RATHER THAN WARNS. ratchet passes
    # an empty key straight through, the client sends "Authorization: Bearer " with nothing after
    # it, every call is refused, and the chain catches each refusal per agent, records the agent as
    # unreachable and runs on to a verdict built out of silence. That is worse than a crash: it
    # produces results that look ordinary, which is how an hour once went by with no credentials at
    # all. The settings page can now drop a key on purpose, so this refusal is not optional.
    #
    # BEFORE THE CLONE, so a misconfigured store costs a line rather than a repository.
    echo "[$slug] no model key on the settings page, in OC_KEY or in PROPOSER_API_KEY; not starting a lane"
    return 1
  fi

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
  # NOT `key`, WHICH IS THE MODEL CREDENTIAL TWENTY LINES ABOVE. This shadowed it and every lane
  # went out carrying the bump key as its API key: four settings scenarios caught it at once, which
  # is what they are for.
  local bslug bkey
  bkey="$repo|$sha|$from|$to"
  bslug=$(printf '%s' "$bkey" | sed 's/[^A-Za-z0-9]\+/_/g')
  echo "bjvagent_$slug" > "$RESULTS/claims/$bslug" 2>/dev/null || echo "[$slug] could not claim"
  trap 'rm -f "$RESULTS/claims/$bslug" 2>/dev/null' RETURN
  # A STALE MARKER WOULD END THE NEXT ROUND BEFORE IT STARTED. It is removed on the way out too;
  # this is the belt for a lane the launcher never got to finish bookkeeping for.
  rm -f "$RESULTS/expiring/$bslug" 2>/dev/null

  # A FRESH CHECKOUT EVERY TIME, EXCEPT WHEN THE RECORD SAYS THIS BUMP IS MID-SENTENCE.
  #
  # The chain reads what each phase did back out of git diff, so a workspace carrying a PREVIOUS
  # ATTEMPT'S edits would attribute them to this run. A round boundary is not a previous attempt: it
  # is this attempt, stopped between stages, with a journal beside it saying which commits belong to
  # which stage. So is a lane that was killed outright, which is the twenty-hour bump the journal
  # was built for and which no launcher has ever actually let resume.
  #
  # WIPING IS ALWAYS SAFE AND KEEPING IS THE RISKY CHOICE, so every doubt falls towards wipe: a
  # wiped tree meets a journal that does not stand on it, the journal is set aside, and the bump
  # starts clean with a real baseline. Falling that way costs time and never a wrong number.
  #
  # `requeued` is deliberately not in the list. That is somebody on a page asking for the work to be
  # done again from the start, and handing them back the state they were discarding is not it.
  local keep=
  case "$(last_state "$bkey")" in
    bumping|paused)
      if [ ! -d "$w/.git" ]; then
        # Silent when there is nothing there at all, which is the ordinary first attempt of a bump
        # whose only row is a heartbeat from a launcher that has since been restarted.
        [ -d "$w" ] && echo "[$slug] the workspace that is there holds no checkout; cloning again"
      elif [ "$(free_gb)" -lt "$WS_FLOOR_GB" ]; then
        echo "[$slug] $(free_gb)GB free is below the ${WS_FLOOR_GB}GB floor; not keeping the workspace"
      elif [ "$(( ( $(date +%s%3N) - $(last_at "$bkey") ) / 3600000 ))" -ge "$ROUND_KEEP_HOURS" ]; then
        echo "[$slug] nobody came back for this workspace within ${ROUND_KEEP_HOURS}h; starting clean"
      else
        keep=1
      fi
      ;;
  esac
  if [ -z "$keep" ]; then
    wipe_ws "$slug"
    # Never echo $origin on failure: it may carry a token.
    if ! clone_repo "$origin" "$w" 2>/dev/null; then
      echo "[$slug] clone failed: $repo"; return 1
    fi
    # NOT ON THE KEPT PATH, and that is the whole point of keeping it: a checkout of the manifest
    # sha would throw away every stage that landed. Where the tree stands is the Java's business
    # from here, and it either resumes onto it or puts it back itself.
    git -C "$w" checkout -q "$sha" 2>/dev/null || { echo "[$slug] no sha $sha"; return 1; }
  else
    echo "[$slug] continuing round $(( $(rounds_done "$bkey") + 1 )) on the workspace that is there"
  fi

  local vols=(-v /var/run/docker.sock:/var/run/docker.sock -v "$w:$w" -v "$RESULTS:$RESULTS"
              -v "$HOPTOOLS:$HOPTOOLS:ro")
  [ -n "${BJV_ITER:-}" ] && [ -d "$BJV_ITER" ] && vols+=(-v "$BJV_ITER:$BJV_ITER")
  [ -d "${BJV_M2:-}" ] && vols+=(-v "$BJV_M2:$BJV_M2")
  [ -e "${BJV_SETTINGS:-}" ] && vols+=(-v "$BJV_SETTINGS:$BJV_SETTINGS:ro")
  [ -d "${BJV_GRADLE_RO:-}" ] && vols+=(-v "$BJV_GRADLE_RO:$BJV_GRADLE_RO:ro")
  [ -d "${BJV_GRADLE_DISTS:-}" ] && vols+=(-v "$BJV_GRADLE_DISTS:$BJV_GRADLE_DISTS")
  [ -e "${BJV_GRADLE_INIT:-}" ] && vols+=(-v "$BJV_GRADLE_INIT:$BJV_GRADLE_INIT:ro")

  # WHAT THIS LANE READ, WHERE THE PAGE CAN SEE IT. Renamed into place rather than written in place,
  # for the same reason as everything else in the run root: a reader holding it open must never see
  # a half-written one.
  # BASHPID AND NOT $$, because one() runs in a background subshell and $$ is the launcher's pid
  # there: eight lanes opening at once would all stage through the same temp name and the mv would
  # race itself.
  local staged=$ROOT/.settings_seen.${BASHPID:-$$}
  settings_stamp > "$staged" 2>/dev/null && mv -f "$staged" "$ROOT/settings_seen" 2>/dev/null
  rm -f "$staged" 2>/dev/null

  local envs=(-e "OC_KEY=$key" -e "OC_BASE=$base" -e "OC_MODEL=$name"
              -e "BJV_HOPTOOLS=$HOPTOOLS"
              -e "BJV_PATIENCE_MINUTES=${BJV_PATIENCE_MINUTES:-45}")
  # OC_BASE and OC_MODEL are NOT in this loop any more: they are set above from the settings page
  # with the environment underneath, and a second copy here would silently win or lose depending on
  # which -e docker saw last.
  # BJV_IMAGE_ID IS IN THIS LIST NOW, AND ITS ABSENCE WAS SILENT. It is resolved at the top of this
  # file and exported, and docker does not inherit the launcher's environment, so "image" was empty
  # on every settlement row ever written. That was cosmetic until a round boundary had to decide
  # whether the pipeline moved: this project iterates by deploying dirty trees, and the live sweep's
  # own record shows a -dirty stamp covering 66 hours and 6129 rows, so the commit alone calls two
  # different images the same pipeline precisely while somebody is iterating.
  #
  # NOTHING ABOUT THE CLOCK IS IN THIS LIST. No budget, no grace, no round cap and no round number.
  # The container has no clock; it reacts to a marker and nothing else, so there is no environment
  # variable, no tool and no prompt through which an agent could learn that a lane is timed.
  for v in BJV_REPO_URL BJV_NET BJV_M2 BJV_SETTINGS BJV_GRADLE_RO BJV_GRADLE_DISTS \
           BJV_GRADLE_INIT BJV_JDK_IMAGE BJV_SCAN_IMAGE BJV_THINKING BJV_HANG_GUARD \
           BJV_BUILD_SECONDS BJV_IMAGE_ID; do
    [ -n "${!v:-}" ] && envs+=(-e "$v=${!v}")
  done

  # THE LANE WATCHES ITSELF, so nothing outside needs the docker socket to stop it. A heartbeat on
  # the claim is what makes "running" observable to a reader that cannot ask the daemon, and the
  # postpone marker is honoured here because this is the one place that already holds the container
  # name and already has docker. A watcher that had to kill lanes needed the socket; a watcher that
  # only writes a marker does not, which is what lets it share a container with a page on the
  # public internet.
  # AND IT WATCHES THE CLOCK, WHICH IS THE ONLY CLOCK ANYTHING IN THIS SYSTEM HAS. At the budget it
  # creates a marker; it does not kill. The container reads that marker BETWEEN STAGES and settles
  # itself, which is worth three things a kill cannot buy. The row: only the Java can compute the
  # pipeline fields the next round's resume decision reads. The loss: a stage in flight finishes and
  # everything landed is already committed, so overshoot is at most one stage and the loss is zero.
  # And the sibling container: the module gate builds through the docker socket, and a lane killed
  # mid-build leaves that build orphaned, which this project has catalogued as a failure class.
  #
  # The kill is the backstop underneath, for a lane that is not reading anything any more.
  local budget watch
  budget=$(round_seconds)
  watch=$(( budget / 20 )); [ "$watch" -lt 1 ] && watch=1; [ "$watch" -gt 30 ] && watch=30
  # IT WAITS FOR THE CONTAINER BEFORE IT DECIDES THE CONTAINER IS GONE, and that was a race this
  # watcher has always had. It is forked and then `docker run` is called, so the FIRST `docker ps`
  # can easily run before the daemon has registered anything: the loop condition is false on entry,
  # the subshell exits, and the lane spends its whole life with no watcher at all. Nothing showed,
  # because a lane that is never postponed and never runs long behaves identically either way.
  ( started=$(date +%s); seen=
    while :; do
      if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^bjvagent_$slug$"; then
        seen=1
      elif [ -n "$seen" ] || [ "$(( $(date +%s) - started ))" -ge 120 ]; then
        break
      fi
      touch "$RESULTS/claims/$bslug" 2>/dev/null
      if [ -e "$RESULTS/postponed/$bslug" ]; then
        echo "[$slug] postponed: $(cut -c1-90 < "$RESULTS/postponed/$bslug" 2>/dev/null)"
        docker rm -f "bjvagent_$slug" >/dev/null 2>&1
        break
      fi
      spent=$(( $(date +%s) - started ))
      if [ "$spent" -ge "$(round_seconds)" ]; then
        [ -e "$RESULTS/expiring/$bslug" ] || : > "$RESULTS/expiring/$bslug"
        if [ "$spent" -ge "$(( $(round_seconds) + ROUND_GRACE_S ))" ]; then
          # A LANE THAT IGNORED THE MARKER FOR THE WHOLE GRACE IS NOT GOING TO STOP ON ITS OWN, and
          # this line is a bug report rather than routine: no stage is meant to outlast a build
          # timeout plus a model's patience. It is also the only hang guard this harness has.
          echo "[$slug] the round did not end at a stage edge; killed"
          docker rm -f "bjvagent_$slug" >/dev/null 2>&1
          break
        fi
      fi
      # THE POLL FOLLOWS THE BUDGET RATHER THAN BEING A SECOND NUMBER TO SET. Thirty seconds against
      # a six-hour round, which is what it was; a second against a scenario whose round is seconds
      # long, which is the only way lanes_test can watch a boundary happen at all.
      sleep "$watch"
    done ) &
  local watchdog=$!
  trap 'kill '"$watchdog"' 2>/dev/null; rm -f "$RESULTS/claims/'"$bslug"'" 2>/dev/null' RETURN

  docker run --rm --name "bjvagent_$slug" \
    "${vols[@]}" "${envs[@]}" \
    "$AGENT_IMAGE" tech.mikhailov.bjv.bump.Bump "$w" "$bkey" "$RESULTS" \
    >> "$ROOT/$slug.log" 2>&1
  rm -f "$RESULTS/claims/$bslug" 2>/dev/null

  # ---- what the round left behind, decided in one place because one place is what ended the lane
  local state round
  state=$(last_state "$bkey")
  if [ -e "$RESULTS/expiring/$bslug" ]; then
    if [ "$state" != "paused" ]; then
      settle_row "$bkey" "paused" \
        "the round ended before the lane reached a stage edge, so it was stopped where it stood; \
everything a stage had landed is committed and the checkout and the journal are kept for the next lane" \
        "$(( $(rounds_done "$bkey") + 1 ))"
      state=paused
    fi
    round=$(rounds_done "$bkey")
    if [ "$MAX_ROUNDS" -gt 0 ] && [ "$round" -ge "$MAX_ROUNDS" ]; then
      # NOT A VERDICT ABOUT THE REPOSITORY, and the page must not read it as one. It says the
      # harness stopped spending after $MAX_ROUNDS rounds with the work unfinished.
      settle_row "$bkey" "out-of-rounds" \
        "$round rounds of the lane budget went by without this bump reaching a verdict, so the \
harness stopped spending on it; nothing here is a judgement about the project" "$round"
      echo "[$slug] out of rounds after $round"
      state=out-of-rounds
    else
      # BACK IN THE QUEUE, WHERE THE PASS CAN REACH IT BEFORE IT ENDS. See drain_round(): the inner
      # loop makes one pass over a manifest of 1400 rows, so a bump paused at row 50 while the pass
      # is at row 900 would otherwise wait for the pass to wrap, which is weeks and usually never.
      printf '%s\t%s\t%s\t%s\t%s\n' "$slug" "$repo" "$sha" "$from" "$to" \
        > "$RESULTS/rounds/$bslug" 2>/dev/null
      echo "[$slug] round $round ended; it comes back when a slot is free"
    fi
    rm -f "$RESULTS/expiring/$bslug" 2>/dev/null
  fi
  # THE WORKSPACE OF A BUMP THAT IS DONE, WHICH NOTHING HAS EVER DELETED. run.sh wiped at the START
  # of an attempt and a settled bump is never attempted again, so every repository this harness has
  # ever touched still had its checkout and its build output on disk: 178 of the 186 directories in
  # the live tree, 27 of its 33 gigabytes, and on a 1400-row corpus a projection past the free space
  # on the root filesystem. Preserving across a round boundary is only affordable because this line
  # exists, and this line is worth having on its own.
  if ! keepable "$state" && [ -z "${BJV_KEEP_WS:-}" ]; then
    wipe_ws "$slug"
  fi
  echo "[$slug] done: $(grep -c . "$ROOT/$slug.log" 2>/dev/null) log lines"
}

# WHETHER THE RECORD SAYS THIS BUMP IS STILL MID-SENTENCE, which is the one question the keep rule
# and the wipe rule are both asking. Stated once so they cannot answer it differently.
keepable() { case "$1" in bumping|paused) return 0 ;; *) return 1 ;; esac; }

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

# HOW MANY LANES ARE RUNNING ANYWHERE, not how many this shell started. `jobs -rp` counts one
# runner's own children, so a second sweep and the rerun drainer each helped themselves to the
# whole allowance: max_lanes 4 across three runners is twelve containers on one GPU, and the
# number on the settings page meant a third of what it said. A claim file exists for the life of
# a lane whoever launched it, and inflight() clears the ones whose container is gone, so the
# claims directory is the count that was wanted all along. Own jobs are the floor, because a
# claim write is allowed to fail and a lane that could not claim still occupies a lane.
running() {
  local n=0 own c name
  for c in "$RESULTS"/claims/*; do
    [ -e "$c" ] || continue
    name=$(cat "$c" 2>/dev/null)
    if [ -n "$name" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$name"; then
      n=$((n+1)); continue
    fi
    # A CLAIM WITH NO CONTAINER, AND NOTHING ELSE WAS GOING TO NOTICE. inflight() reaps one, but
    # only for a bump the pass actually considers, and a pass skips a SETTLED bump before it gets
    # there. So a lane that ended left its claim behind for good: measured, one dead ForgeHaxEx
    # claim held a slot for nine hours and a cap of eight was really a cap of seven.
    #
    # NOT REAPED ON SIGHT, THOUGH. A lane writes its claim before it starts its container, so a
    # claim with no container is either dead or three seconds old. The watchdog touches a live one
    # every thirty seconds, so an untouched-for-minutes claim is the only one that is certainly
    # dead; reaping the other kind would launch the same bump twice.
    if [ -n "$(find "$c" -mmin +3 2>/dev/null)" ]; then
      echo "[lanes] reaping a claim whose container is gone: $(basename "$c")"
      rm -f "$c" 2>/dev/null
    else
      n=$((n+1))
    fi
  done
  own=$(jobs -rp | wc -l)
  [ "$n" -gt "$own" ] && echo "$n" || echo "$own"
}

# WHETHER SOMEBODY IS WAITING ON A PAGE FOR A LANE.
#
# A rerun is a person who clicked, and it was losing a three-way race for every freed slot against
# two sweeps of 1439 rows each. Measured twice: a requeued bump sat unclaimed while the sweeps took
# every slot that came up.
#
# So a sweep holds one slot back while anything is queued for rerun, and the drainer takes it. The
# total is unchanged, which is the point: raising the cap for reruns would quietly spend GPU that
# was deliberately budgeted.
rerunWaiting() {
  [ -s "$RESULTS/rerun.tsv" ] && return 0
  ls "$ROOT"/rerun-batch-*.tsv >/dev/null 2>&1 && return 0
  return 1
}

# The ceiling this runner may fill, which is not always the whole allowance.
#
# IT HAS TO WORK WITHOUT EVERY RUNNER AGREEING, which is the part the first attempt got wrong. A
# reservation where sweeps hold a slot back is the tidy design and it was inert on arrival: bash
# reads a script by byte offset, the two long-lived sweeps were already inside their loops, and
# they will not see a new function until they are restarted, which means killing live lanes.
#
# So priority takes rather than waits. The drainer may fill one slot above the allowance, which
# needs nobody's cooperation and works the moment it is deployed. A sweep that IS new holds one
# back while a rerun is queued, so once the sweeps do restart the total returns to the allowance
# exactly. Both halves are correct on their own and together; the cost, said plainly, is one lane
# over the budget for as long as a person is waiting on a page and an old sweep is still running.
mine() {
  local cap; cap=$(lanes)
  if [ -n "${BJV_PRIORITY:-}" ]; then
    echo $((cap + 1))
  elif [ "$cap" -gt 1 ] && rerunWaiting; then
    echo $((cap - 1))
  else
    echo "$cap"
  fi
}

# WORK THAT IS WAITING FOR ITS NEXT ROUND, and whether any of it is eligible yet.
#
# A paused bump has to be re-offered by something, and the manifest pass cannot be that something:
# it makes ONE pass over 1400 rows, so a bump paused at row 50 while the pass is at row 900 would
# not be reconsidered until the pass wrapped, which at four lane-hours a bump and eight lanes is
# about seven hundred hours and usually never, because the loop would have exited first. A feature
# that pauses bumps and never picks them up is strictly worse than no feature.
#
# A DIRECTORY OF MARKERS, LIKE claims AND postponed, AND NOT A TSV. One file per bump means the
# newest boundary overwrites the older one rather than queueing a duplicate, unlinking is atomic,
# and the file's own mtime is the boundary time, so there is no timestamp to keep in step with
# anything. A launcher writes and removes them and nothing else does.
roundsWaiting() {
  local f
  for f in "$RESULTS"/rounds/*; do [ -e "$f" ] && return 0; done
  return 1
}

# ONE PAUSED BUMP, LAUNCHED, IF ONE IS DUE. Called once per manifest row the pass launches, so
# resumes can never take more than half the launches and nothing has to count anything.
#
# A row is due one whole budget after its boundary: other work gets first refusal for a round's
# worth of time, and the workspace stays warm for hours rather than weeks.
drain_round() {
  local f b rslug rrepo rsha rfrom rto
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    b=$(basename "$f")
    read -r rslug rrepo rsha rfrom rto < "$f" 2>/dev/null || continue
    [ -n "${rto:-}" ] || { rm -f "$f" 2>/dev/null; continue; }
    # A bump that settled while it waited is not work any more, whatever put it here.
    if settled "$rrepo"; then rm -f "$f" 2>/dev/null; continue; fi
    inflight "$b" && continue
    postponed "$b" && continue
    # REMOVED BEFORE IT IS LAUNCHED, so a marker cannot be drained twice; the lane writes a fresh
    # one if it pauses again, and the new marker's mtime restarts the cool-down.
    rm -f "$f" 2>/dev/null
    while [ "$(running)" -ge "$(mine)" ]; do sleep 2; done
    one "$rslug" "$rrepo" "$rsha" "$rfrom" "$rto" &
    return 0
  done <<EOF
$(find "$RESULTS/rounds" -maxdepth 1 -type f ! -newermt "$(round_due_before)" 2>/dev/null | sort)
EOF
  return 1
}

echo "manifest $MAN ($(grep -c . "$MAN") rows), $(lanes) lanes (live: $LANEFILE), results -> $RESULTS"
# TWO ROWS CANNOT SHARE A WORKSPACE NOW THAT A WORKSPACE OUTLIVES A LANE. ws is keyed by the
# manifest's own slug rather than by the bump key, which was harmless while the directory was wiped
# at the start of every attempt and is not any more: two rows with one slug would take turns
# resuming onto each other's tree. They would degrade safely -- the journals are separate, so the
# second one's does not stand on the tree and the bump restarts -- but it would thrash, silently.
dupes=$(cut -f1 "$MAN" | sort | uniq -d | head -5 | tr '\n' ' ')
[ -n "${dupes// /}" ] && echo "WARNING: the manifest reuses these slugs, and a slug is a workspace: $dupes"
# THE PASSES OVER THE MANIFEST, WHICH ARE NOT THE ROUNDS OF A BUMP. This counter was called rounds
# and now shares a file with a feature that means something else by the word.
passes=0
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
  while [ "$(running)" -ge "$(mine)" ]; do sleep 2; done
  one "$slug" "$repo" "$sha" "$from" "$to" &
  done_n=$((done_n+1))
  # ONE FOR ONE, INTERLEAVED WITH THE PASS. Work that has already had a lane must not crowd out work
  # that has never had one, and work that has never had one must not make a paused bump wait for the
  # pass to wrap. Alternating is both, and it needs nothing counted.
  drain_round && done_n=$((done_n+1))
done < "$MAN"
# A PASS THAT HAS RUN OUT OF ROWS HAS NOT RUN OUT OF WORK, and draining before looking again is
# what left seven of sixteen slots idle for hours. The rows a supervisor set aside are exactly the
# work those slots could do, and they are only reconsidered on the next pass, which this `wait`
# would not let start until the slowest straggler exited.
#
# THE SAME REASONING AS THE GUARD INSIDE THE LOOP, one level up. That guard was deliberately
# changed from parking on a completion to polling, because max_lanes was being re-read at exactly
# the moments its value could not matter. This was the other such moment.
#
# So when something is set aside, wait only for a slot rather than for the tail. When nothing is,
# there is no next pass to start and draining is the honest thing to do.
#
# A PAUSED BUMP COUNTS AS SUCH WORK. Its lane may still be running, and the marker it will write is
# written when that lane ENDS, so draining here would decide there is nothing waiting a minute
# before there is.
if [ "$postponed_n" -gt 0 ] || roundsWaiting; then
  while [ "$(running)" -ge "$(mine)" ]; do sleep 2; done
else
  wait
fi
echo "manifest complete: $done_n launched, $skipped already settled, $postponed_n postponed"

# NOTHING LEFT BUT THE ONES THAT WERE SET ASIDE. A postponement frees a slot for work that can
# progress; it is not a way to drop a repo from the corpus. Once the queue holds nothing else, the
# reason to keep them aside is gone and they are the work. Bounded, because a bump that is postponed
# again the moment it starts would otherwise loop here forever.
  passes=$((passes+1))
  # WORK THAT IS COMING BACK KEEPS THE LOOP ALIVE, and it is not bounded the way a postponement is.
  # A postponed bump can be postponed again the instant it starts, which is why that path stops
  # after three passes; a paused one has spent a whole budget of real work first and is bounded by
  # the round cap instead. Nothing else here may exit while a bump is waiting for its next round.
  if roundsWaiting; then
    if ! drain_round && [ "$done_n" -eq 0 ]; then
      # Nothing is due yet and there is nothing else to do: wait rather than spin the manifest.
      # A quarter of a round, so a six-hour budget waits a minute at most and a scenario waits less.
      idle=$(( $(round_seconds) / 4 )); [ "$idle" -lt 1 ] && idle=1; [ "$idle" -gt 60 ] && idle=60
      sleep "$idle"
    fi
    continue
  fi
  # ONLY WHEN THEY ARE ALL THAT IS LEFT. A postponement frees a slot for work that can progress, so
  # it must outlast every round that still had such work: clearing it after a pass that launched 500
  # repos hands the slot straight back to the lane that was going nowhere. The smoke test caught
  # this on its first run -- a pass that skipped one bump and launched another cleared the marker
  # regardless, which made postponing mean nothing at all.
  # TWO DECISIONS, AND THEY WERE ONE. Whether to go round again, and whether to CLEAR what was set
  # aside, are not the same question, and conflating them is what made a productive pass exit with
  # work still on the table. Going again is right whenever something is set aside: the pass may
  # have launched five hundred repos and still be leaving three slots empty. Clearing is the
  # narrow one and keeps its old rule.
  if [ "$postponed_n" -eq 0 ] || [ "$passes" -ge 3 ]; then
    [ "$postponed_n" -gt 0 ] && echo "$postponed_n left postponed; they run when nothing else is waiting"
    break
  fi
  if [ "$done_n" -gt 0 ]; then
    # NOT CLEARED AFTER A PASS THAT LAUNCHED SOMETHING. A postponement frees a slot for work that
    # can progress, so it has to outlast every round that still had such work; clearing it here
    # would hand the slot straight back to the lane that was going nowhere. The pass goes again
    # anyway, and a bump still set aside is simply skipped again, cheaply.
    echo "$done_n launched, $postponed_n still set aside; going again without clearing them"
    continue
  fi
  echo "only postponed bumps remain ($postponed_n); clearing them and going again"
  # THE MARKERS ARE ROOT-OWNED. The supervisor writes them from inside a container, so the launcher
  # -- a host user -- cannot remove them, and this clearing silently did nothing at all. The same
  # asymmetry the results tree already handles once at startup, and the smoke test could not catch
  # it because its own markers were host-written.
  docker run --rm -v "$RESULTS/postponed:/p" alpine sh -c 'rm -f /p/*' >/dev/null 2>&1 \
    || rm -f "$RESULTS/postponed"/* 2>/dev/null
done
