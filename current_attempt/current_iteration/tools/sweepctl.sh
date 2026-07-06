#!/bin/bash
# sweepctl.sh stop|start|restart [runroot] -- the ONE sanctioned way to pause/resume the sweep.
# Contract (AGENTS.md P9): full stop = loop + lanes + scorers + agent containers, then purge partial
# slugs AND kill-artifact NO_RESULT skips (a scorer whose host died mid-kill writes NO_RESULT, which the
# resume guard would otherwise freeze as done). Run as a FILE (bash tools/sweepctl.sh ...) so the pkill
# patterns never appear in an ssh command line (the self-match trap).
set -uo pipefail
I=/home/vmihaylov/bump-java-version/current_attempt/current_iteration
CMD=${1:-status}; RUN=${2:-$I/runs}
stop_all() {
  pkill -f 'rung2/roundrobin[.]sh'; pkill -f 'rung2/run_repo[.]sh'
  pkill -f 'rung2/rung2_host[.]sh'; pkill -f 'rung2_one_scored[.]sh'; sleep 3
  docker ps --format '{{.Names}}' | grep -E '^bjv(agent|job)' | xargs -r docker rm -f >/dev/null 2>&1
  local n=0
  for d in "$RUN/hoptest"/rr_*_*; do
    [ -d "$d" ] || continue
    if [ ! -f "$d/verdict.txt" ] && [ ! -f "$d/skip.json" ]; then
      docker run --rm -v "$RUN/hoptest:/h" alpine rm -rf "/h/$(basename "$d")"; n=$((n+1))
    elif grep -q '"NO_RESULT"' "$d/skip.json" 2>/dev/null; then
      docker run --rm -v "$RUN/hoptest:/h" alpine rm -rf "/h/$(basename "$d")"; n=$((n+1))
    fi
  done
  echo "stopped clean; purged $n partial/NO_RESULT slugs"
}
start_all() {
  cd "$I" && BJV_RUNROOT="$RUN" BJV_QUEUE_STREAMING="${BJV_QUEUE_STREAMING:-0}" setsid nohup bash rung2/roundrobin.sh "${BJV_TARGET:-1600}" 4 20 5 >> "$RUN/rr_logs/roundrobin.log" 2>&1 < /dev/null &
  sleep 15
  echo "instances: $(pgrep -cf 'rung2/roundrobin[.]sh')"
  tail -2 "$RUN/rr_logs/roundrobin.log"
}
case "$CMD" in
  stop) stop_all;;
  start) start_all;;
  restart) stop_all; start_all;;
  status) echo "instances=$(pgrep -cf 'rung2/roundrobin[.]sh') lanes=$(pgrep -cf 'rung2/run_repo[.]sh') agents=$(docker ps --format '{{.Names}}'|grep -c '^bjvagent_')";;
esac
