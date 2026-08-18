#!/usr/bin/env bash
# rerun.sh — drain what the dashboard's rerun button asked for.
#
# A settled bump is skipped forever, which is right while the harness is unchanged and wrong the
# moment it is not. The button appends a row to results/rerun.tsv and marks the bump requeued; this
# is what turns that into work. It is separate from run.sh on purpose: run.sh reads its manifest
# once and cannot be injected into mid-pass, and a sweep of 1439 rows is not something to restart
# because one repo needs looking at again.
#
# BATCHES OVERLAP. run.sh waits for every lane it launched before it returns, so draining one batch
# at a time meant a click waited on a bump it had nothing to do with: two requeued repositories sat
# unclaimed for ninety minutes behind a single ForgeHaxEx that was still going. Each batch now runs
# in the background and the lane cap does the bounding, which is what it is for -- it counts claim
# files, so every runner against this run root shares one allowance rather than helping itself to
# the whole of it.
#
# Batch files are numbered rather than reused, because a second drain starting while the first is
# still reading its manifest would otherwise rewrite the file underneath it.
set -uo pipefail
cd "$(dirname "$0")"
R=${BJV_RUNROOT:-$(cd .. && pwd)/runs/agent}
n=0

# ADOPT WHAT A PREVIOUS DRAINER LEFT BEHIND.
#
# A batch is MOVED out of rerun.tsv before it runs, so a drainer that is restarted while one is in
# flight strands it: the file is no longer in the queue and the new drainer only watches the queue.
# Measured the first time this was restarted, with somebody waiting on the page for the bump inside.
#
# Re-queueing is safe rather than clever. run.sh skips a bump that has settled and skips one whose
# claim is live, so a row that did finish costs one line of output and a row still running is left
# alone; the only case that changes is the one this is for.
for old in "$R"/rerun-batch-*.tsv; do
  [ -e "$old" ] || continue
  echo "[$(date -Is)] adopting $(basename "$old"): $(grep -c . "$old") row(s) a previous drainer left"
  cat "$old" >> "$R/results/rerun.tsv"
  rm -f "$old"
done

while true; do
  if [ -s "$R/results/rerun.tsv" ]; then
    n=$((n+1))
    BATCH=$R/rerun-batch-$n.tsv
    # MOVED, NOT COPIED, so a click landing while this batch starts joins the next one rather than
    # being run twice or dropped.
    mv "$R/results/rerun.tsv" "$BATCH"
    echo "[$(date -Is)] draining batch $n: $(grep -c . "$BATCH") requeued bump(s)"
    # PRIORITY, WHICH MEANS A SWEEP HOLDS A SLOT BACK RATHER THAN THIS ONE TAKING AN EXTRA. A
    # rerun is a person who clicked, and it was losing a three-way race for every freed lane to two
    # sweeps of 1439 rows each. The total allowance is unchanged.
    BJV_PRIORITY=1 bash ./run.sh "$BATCH" &
  fi
  sleep 15
done
