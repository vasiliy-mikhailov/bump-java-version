#!/usr/bin/env bash
# rerun.sh — drain what the dashboard's rerun button asked for.
#
# A settled bump is skipped forever, which is right while the harness is unchanged and wrong the
# moment it is not. The button appends a row to results/rerun.tsv and marks the bump requeued; this
# is what turns that into work. It is separate from run.sh on purpose: run.sh reads its manifest
# once and cannot be injected into mid-pass, and a sweep of 1439 rows is not something to restart
# because one repo needs looking at again.
#
# It shares the run root with whatever else is sweeping, so the claim files keep two runners off the
# same bump, and max_lanes bounds them together rather than each.
set -uo pipefail
cd "$(dirname "$0")"
R=${BJV_RUNROOT:-$(cd .. && pwd)/runs_agent}
BATCH=$R/rerun-batch.tsv
while true; do
  if [ -s "$R/results/rerun.tsv" ]; then
    # MOVED, NOT COPIED, so a click landing while this batch runs starts the next one rather than
    # being run twice or dropped.
    mv "$R/results/rerun.tsv" "$BATCH"
    echo "[$(date -Is)] draining $(grep -c . "$BATCH") requeued bump(s)"
    bash ./run.sh "$BATCH"
  fi
  sleep 15
done
