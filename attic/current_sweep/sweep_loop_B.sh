#!/usr/bin/env bash
# Loop B (inner-loop throughput sweep). Each round draws a fresh N-datapoint iter from the outer
# corpus store, runs the agent driver as openhands over it, repeats. Paths: sweep_env.sh.
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sweep_env.sh"
set -a; source "$ROOT/.env"; set +a
export OC_KEY="$PROPOSER_API_KEY"
LOG=/var/log/observe/app/panel_loop_B.log
N=${1:-100}; K=${2:-4}
r=0
while true; do
  r=$((r+1))
  python3 "$SWEEP/draw_iter.py" --n=$N --seed=$(( $(date +%s) + 54321 )) --out="$DATASET_B" >> "$LOG" 2>&1
  echo "=== B ROUND $r START $(date -u +%FT%TZ) (openhands N=$N K=$K) ===" >> "$LOG"
  OC_KEY="$OC_KEY" OUT_SUFFIX=_B DATASET_FILE="$DATASET_B" python3 "$SWEEP/agent_sweep.py" openhands 100000 "$K" >> "$LOG" 2>&1
  echo "=== B ROUND $r DONE $(date -u +%FT%TZ) ===" >> "$LOG"
done
