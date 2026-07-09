#!/usr/bin/env bash
# Regression test for rung2/roundrobin.sh INDEPENDENT LANES (no terminal join).
# A slow-but-legitimate lane (RLVR: uncapped, may run for hours) must NOT hold the round open, and a relaunch
# while it is still running must SKIP it (not double-run). Uses a stub run_repo; no docker/builds. ~70s.
# Usage: test_independent_lanes.sh [path/to/roundrobin.sh]
set -u
RR=${1:-"$(cd "$(dirname "$0")" && pwd)/../rung2/roundrobin.sh"}
[ -f "$RR" ] || { echo "roundrobin not found: $RR"; exit 2; }
T=$(mktemp -d); mkdir -p "$T"/q "$T"/hoptest "$T"/rr_logs
cat > "$T/stub_runrepo.sh" <<'STUB'
#!/bin/bash
REPO=$1; SHA=$2; SLUG=$3; RUN=${BJV_RUNROOT}
O=$RUN/hoptest/$SLUG; mkdir -p "$O"
if [ "$REPO" = slowrepo ]; then sleep 45; else sleep 2; fi
printf '{"slug":"%s","repo":"%s","verdict":"PASS"}\n' "$SLUG" "$REPO" > "$O/score.json"
STUB
chmod +x "$T/stub_runrepo.sh"
# exercise the REAL dispatcher, but swap run_repo for the stub
sed "s#bash \$CI/rung2/run_repo.sh#bash $T/stub_runrepo.sh#" "$RR" > "$T/rr_test.sh"
printf 'repoA x\nslowrepo x\nrepoB x\nrepoC x\n' > "$T/q/cand_8.txt"
: > "$T/q/cand_11.txt"; : > "$T/q/cand_17.txt"; : > "$T/q/cand_21.txt"
echo 2 > "$T/q/max_lanes"

pass=0; fail=0
chk(){ if [ "$2" = "$3" ]; then echo "  PASS $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1 (got '$2' want '$3')"; fail=$((fail+1)); fi; }

echo "=== RUN 1: dispatch; must return without joining the 45s straggler ==="
s=$SECONDS
BJV_RUNROOT="$T" bash "$T/rr_test.sh" 100 2 20 1 > "$T/run1.log" 2>&1
d1=$(( SECONDS - s ))
[ "$d1" -lt 40 ] && chk "dispatcher_returned_fast(${d1}s)" yes yes || chk "dispatcher_returned_fast(${d1}s)" no yes
chk "score_jsons_after_dispatch" "$(ls "$T"/hoptest/*/score.json 2>/dev/null | wc -l | tr -d ' ')" 3
chk "straggler_no_score_yet"  "$([ -f "$T/hoptest/rr_8_1/score.json" ] && echo yes || echo no)" no
chk "straggler_inflight_live" "$([ -e "$T/hoptest/rr_8_1/.inflight" ] && kill -0 "$(cat "$T/hoptest/rr_8_1/.inflight" 2>/dev/null)" 2>/dev/null && echo yes || echo no)" yes
grep -q DISPATCH_DONE "$T/run1.log" && chk "run1_dispatch_done_printed" yes yes || chk "run1_dispatch_done_printed" no yes

echo "=== RUN 2: relaunch WHILE straggler still running; must skip it, not re-dispatch ==="
pid_before=$(cat "$T/hoptest/rr_8_1/.inflight" 2>/dev/null)
BJV_RUNROOT="$T" bash "$T/rr_test.sh" 100 2 20 1 > "$T/run2.log" 2>&1
pid_after=$(cat "$T/hoptest/rr_8_1/.inflight" 2>/dev/null)
chk "straggler_pid_unchanged" "$pid_before" "$pid_after"
grep -q "bite jv8: slowrepo" "$T/run2.log" && chk "run2_did_NOT_redispatch" redispatched skipped || chk "run2_did_NOT_redispatch" skipped skipped

echo "=== straggler finishes on its own; verify completion + self-clean ==="
for i in $(seq 1 60); do [ -f "$T/hoptest/rr_8_1/score.json" ] && break; sleep 1; done
chk "straggler_completed" "$([ -f "$T/hoptest/rr_8_1/score.json" ] && echo yes || echo no)" yes
chk "straggler_inflight_cleared" "$([ -e "$T/hoptest/rr_8_1/.inflight" ] && echo yes || echo no)" no
chk "final_total" "$(ls "$T"/hoptest/*/score.json 2>/dev/null | wc -l | tr -d ' ')" 4

echo "=== RUN 3: relaunch after done; all skipped via score.json, nothing re-run ==="
BJV_RUNROOT="$T" bash "$T/rr_test.sh" 100 2 20 1 > "$T/run3.log" 2>&1
chk "no_reruns_after_complete" "$(ls "$T"/hoptest/*/score.json 2>/dev/null | wc -l | tr -d ' ')" 4

echo "==== RESULT: $pass passed, $fail failed ===="
rm -rf "$T"
[ "$fail" = 0 ]
