#!/bin/bash
# Balanced round-robin over the 4 hop queues: each bite goes to the hop with the FEWEST (completed+inflight)
# datapoints, screens inline (>=MINTESTS green), migrates via the two-step run_repo, scores. Even by hop until
# the small queues exhaust, then continues on the big ones. Args: TARGET [JOBS=4] [LOADCAP=20] [MINTESTS=5]
CI=/home/vmihaylov/bump-java-version/current_attempt/current_iteration
RUN=${BJV_RUNROOT:-$CI/runs}; mkdir -p $RUN/q $RUN/hoptest $RUN/rr_logs
TARGET=${1:-200}; JOBS=${2:-4}; CAP=${3:-20}; export BJV_MIN_TESTS=${4:-5}
# --- LIVE-ADJUSTABLE lane cap: a lane launches only while in-flight lanes < the number in $MAXFILE, which is
# re-read every iteration so the operator can raise/lower concurrency WITHOUT restarting the sweep
# (echo N > $MAXFILE). Falls back to the JOBS arg if the file is missing/empty/non-numeric. No load gate.
# Contract: AGENTS.md concurrency clause. ---
MAXFILE=${BJV_MAXLANES_FILE:-$RUN/q/max_lanes}
[ -f "$MAXFILE" ] || echo "$JOBS" > "$MAXFILE"
maxlanes(){ local m; m=$(cat "$MAXFILE" 2>/dev/null); case "$m" in ""|*[!0-9]*|0) echo "$JOBS";; *) echo "$m";; esac; }
declare -A CUR LAUNCHED NEXT
for h in 8 11 17 21; do CUR[$h]=1; LAUNCHED[$h]=0; done
qlen(){ wc -l < $RUN/q/cand_$1.txt 2>/dev/null || echo 0; }
donec(){ ls $RUN/hoptest/rr_$1_*/score.json 2>/dev/null | wc -l; }
finc(){ ls $RUN/hoptest/rr_$1_*/score.json $RUN/hoptest/rr_$1_*/skip.json 2>/dev/null | wc -l; }
totdone(){ ls $RUN/hoptest/rr_*_*/score.json 2>/dev/null | wc -l; }
load1(){ awk '{print int($1)}' /proc/loadavg; }
# --- INDEPENDENT LANES (AGENTS.md stoicism clause: no cap on the agent -- this changes ORCHESTRATION only).
# Each lane is detached and finishes on its own; the dispatcher NEVER joins it, so a slow-but-legitimate lane
# (RLVR: uncapped, may run for hours) can never hold the round open. A per-slug .inflight marker holds the
# wrap-subshell PID; lane_live lets a relaunch skip a still-running straggler instead of double-running it,
# and self-reaps a stale marker (dead PID) so a killed lane's candidate is re-runnable. ---
lane_live(){ local p; p=$(cat "$RUN/hoptest/$1/.inflight" 2>/dev/null) || return 1; { [ -n "$p" ] && kill -0 "$p" 2>/dev/null; } && return 0; rm -f "$RUN/hoptest/$1/.inflight"; return 1; }
while true; do
  td=$(totdone); [ "$td" -ge "$TARGET" ] && break
  avail=''; for h in 8 11 17 21; do [ "${CUR[$h]}" -le "$(qlen $h)" ] && avail="$avail $h"; done
  # streaming mode: the queues are a LIVE stream (a feeder appends rows as the dig emits them), so an
  # empty moment means wait for the producer, not end-of-dataset. Queues are append-only, which keeps
  # slug = line index deterministic. Exit stays TARGET (or sweepctl stop).
  if [ -z "$avail" ]; then
    [ "${BJV_QUEUE_STREAMING:-0}" = 1 ] && { sleep 60; continue; }
    break
  fi
  while [ "$(jobs -rp | wc -l)" -ge "$(maxlanes)" ]; do sleep 8; done   # live-adjustable cap via $MAXFILE (echo N > it); no load gate
  best=''; bestm=999999
  for h in $avail; do m=$(( $(donec $h) + LAUNCHED[$h] - $(finc $h) )); [ "$m" -lt "$bestm" ] && { bestm=$m; best=$h; }; done
  h=$best
  line=$(sed -n "${CUR[$h]}p" $RUN/q/cand_$h.txt); CUR[$h]=$(( CUR[$h] + 1 ))
  repo=${line%% *}; sha=${line##* }; [ -z "$repo" ] && continue
  slug=rr_${h}_${LAUNCHED[$h]}; LAUNCHED[$h]=$(( LAUNCHED[$h] + 1 ))
  { [ -f $RUN/hoptest/$slug/score.json ] || [ -f $RUN/hoptest/$slug/skip.json ] || lane_live "$slug"; } && continue   # RESUME GUARD: skip done OR still-live in-flight candidates
  echo "[$td/$TARGET | jv8=$(donec 8) jv11=$(donec 11) jv17=$(donec 17) jv21=$(donec 21) | load $(load1)] bite jv$h: $repo"
  mkdir -p $RUN/hoptest/$slug
  # subshell fully redirected to its own lane log, so a detached straggler never holds the dispatcher's stdout open
  ( echo $BASHPID > $RUN/hoptest/$slug/.inflight; bash $CI/rung2/run_repo.sh "$repo" "$sha" "$slug"; rm -f $RUN/hoptest/$slug/.inflight ) >$RUN/rr_logs/$slug.log 2>&1 &
  sleep 4
done
# INDEPENDENT LANES: do NOT `wait` on stragglers. Dispatch is done (queue drained / TARGET hit); any lane still
# running finishes detached and writes its own score.json/skip.json, self-clearing its .inflight marker. A single
# multi-hour reasoning-runaway lane no longer freezes completion or the next round (a relaunch skips it via lane_live).
still=0; for m in "$RUN"/hoptest/*/.inflight; do [ -e "$m" ] || continue; p=$(cat "$m" 2>/dev/null); { [ -n "$p" ] && kill -0 "$p" 2>/dev/null; } && still=$((still+1)); done
echo "ROUNDROBIN_DISPATCH_DONE total=$(totdone) jv8=$(donec 8) jv11=$(donec 11) jv17=$(donec 17) jv21=$(donec 21) still_finishing=$still (detached; not joined)"
