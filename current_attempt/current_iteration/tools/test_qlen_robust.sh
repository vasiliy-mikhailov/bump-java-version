#!/usr/bin/env bash
# Regression guard for rung2/roundrobin.sh: a transient `wc`/fork failure (host fork-storm, load 3000+, EPERM
# "Operation not permitted") must NOT be read as "queue drained". A load spike once false-completed a round and
# dropped 276 queued candidates. With a broken `wc` and a full queue the dispatcher must keep running (not
# declare completion); with a working `wc` it must process the queue and complete cleanly.
# Usage: test_qlen_robust.sh [path/to/roundrobin.sh]
set -u
RR=${1:-"$(cd "$(dirname "$0")" && pwd)/../rung2/roundrobin.sh"}
[ -f "$RR" ] || { echo "roundrobin not found: $RR"; exit 2; }
T=$(mktemp -d); mkdir -p "$T"/q "$T"/hoptest "$T"/rr_logs "$T"/fakebin
printf '#!/bin/sh\necho "Operation not permitted" >&2\nexit 126\n' > "$T/fakebin/wc"; chmod +x "$T/fakebin/wc"
cat > "$T/stub.sh" <<'STUB'
#!/bin/bash
O=${BJV_RUNROOT}/hoptest/$3; mkdir -p "$O"; sleep 1; printf '{"verdict":"PASS"}\n' > "$O/score.json"
STUB
chmod +x "$T/stub.sh"
sed "s#bash \$CI/rung2/run_repo.sh#bash $T/stub.sh#" "$RR" > "$T/rr.sh"
seed(){ printf 'a x\nb x\nc x\nd x\ne x\n' > "$T/q/cand_8.txt"; : > "$T/q/cand_11.txt"; : > "$T/q/cand_17.txt"; : > "$T/q/cand_21.txt"; echo 2 > "$T/q/max_lanes"; rm -f "$T"/hoptest/*/score.json 2>/dev/null; }
pass=0; fail=0
chk(){ if [ "$2" = "$3" ]; then echo "  PASS $1"; pass=$((pass+1)); else echo "  FAIL $1 (got '$2' want '$3')"; fail=$((fail+1)); fi; }

echo "=== broken wc + full queue: dispatcher must NOT false-complete ==="
seed
timeout 12 env PATH="$T/fakebin:$PATH" BJV_RUNROOT="$T" bash "$T/rr.sh" 100 2 20 1 > "$T/broke.log" 2>&1
rc=$?
grep -q 'DISPATCH_DONE\|ROUNDROBIN_DONE' "$T/broke.log" && chk "no_false_completion_under_broken_wc" completed survived || chk "no_false_completion_under_broken_wc" survived survived
[ "$rc" = 124 ] && chk "kept_running_until_timeout(rc=$rc)" yes yes || chk "kept_running_until_timeout(rc=$rc)" no yes

echo "=== working wc: processes the whole queue and completes cleanly (no regression) ==="
seed
timeout 60 env BJV_RUNROOT="$T" bash "$T/rr.sh" 100 2 20 1 > "$T/ok.log" 2>&1
chk "processes_all" "$(ls "$T"/hoptest/*/score.json 2>/dev/null|wc -l|tr -d ' ')" 5
grep -q 'DISPATCH_DONE' "$T/ok.log" && chk "completes_when_truly_drained" yes yes || chk "completes_when_truly_drained" no yes

echo "==== $pass passed, $fail failed ===="
rm -rf "$T"
[ "$fail" = 0 ]
