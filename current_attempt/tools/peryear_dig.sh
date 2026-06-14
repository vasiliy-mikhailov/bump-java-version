#!/usr/bin/env bash
# Per-year baseline dig. Repeatedly samples fresh compiling + test-bearing baselines from the
# discovered candidate pool into the corpus store, skipping already-dug repos (resume-safe).
# All paths come from env.sh (single source of truth).
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"
LOG="$OBS/peryear_dig.log"
cd "$ROOT"
while true; do
  python3 "$TOOLS/dig_remaining.py" >> "$LOG" 2>&1 || { sleep 60; continue; }
  n=$(wc -l < "$REMAINING")
  if [ "${n:-0}" -le 0 ]; then echo "=== all candidates dug; sleep 1800 $(date -u +%FT%TZ) ===" >> "$LOG"; sleep 1800; continue; fi
  echo "=== DIG CHUNK START $(date -u +%FT%TZ) remaining=$n ===" >> "$LOG"
  python3 "$TOOLS/sample_shas.py" --seed=0 --max-attempts=2 --scan-cap=15 --min-tests=3 \
    --workers=4 --limit=300 --repos-file="$REMAINING" --out="$STORE_BASE" >> "$LOG" 2>&1
  echo "=== DIG CHUNK DONE $(date -u +%FT%TZ) store=$(wc -l < "$STORE") ===" >> "$LOG"
done
