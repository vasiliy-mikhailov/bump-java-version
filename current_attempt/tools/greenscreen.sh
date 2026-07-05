#!/bin/bash
# greenscreen.sh <workdir> <jv_from> [min_green=5] [outdir=<workdir>/.greenscreen]
# The SWEEP-FIDELITY baseline screen: runs exactly what rung1lib r1_baseline runs (sealed bjv-alljdk env,
# `bjv from build` + `bjv from test` + score.py passet), so a sha the dig emits is a sha the sweep will
# reproduce. Prints one of:  PRE=<n> | NOCOMPILE | BASELINE_TIMEOUT   (exit 0 iff PRE >= min_green).
# Screening is deterministic infra, so it takes a bound: BJV_INNER (default 900s) caps each build/test leg.
# Single source of truth note: this mirrors r1_baseline verbatim; when the current sweep run finishes,
# r1_baseline should delegate here so the two can never drift.
set -uo pipefail
WS=$1; JV=$2; MIN=${3:-5}; OD=${4:-$WS/.greenscreen}
I=/home/vmihaylov/bump-java-version/current_attempt/current_iteration
export PATH="$I/hoptools:$PATH"
case "$JV" in 8) TO=11;; 11) TO=17;; 17) TO=21;; 21) TO=25;; *) echo "UNSUPPORTED_JV"; exit 2;; esac
export BJV_WS=$WS BJV_FROM=$JV BJV_TO=$TO BJV_NET=mvn-cache \
  BJV_M2=/home/vmihaylov/.m2-fitness BJV_SETTINGS=/home/vmihaylov/maven-config/settings.xml \
  BJV_GRADLE_RO=/home/vmihaylov/.gradle-fitness BJV_GRADLE_DISTS=/home/vmihaylov/.gradle-dists \
  BJV_INNER=${BJV_INNER:-900}
mkdir -p "$OD"
# phantom-baseline guard: drop COMMITTED stale test reports so passet reflects a real run (same as r1_baseline)
find "$WS" \( -path '*/target/surefire-reports' -o -path '*/build/test-results' \) -type d -exec rm -rf {} + 2>/dev/null
bjv from build >"$OD/pre_build.log" 2>&1 || { echo NOCOMPILE; exit 1; }
bjv from test  >"$OD/pre_test.log"  2>&1; PTRC=$?
if [ "$PTRC" = 124 ] || [ "$PTRC" = 137 ]; then echo BASELINE_TIMEOUT; exit 1; fi
docker run --rm -v "$WS:$WS" -v "$I/tools:/t:ro" -v "$OD:$OD" python:3-slim \
  python3 /t/score.py passet "$WS" "$OD/pre_set.txt" >/dev/null 2>&1
N=$(grep -c . "$OD/pre_set.txt" 2>/dev/null || echo 0)
find "$WS" \( -path '*/target/surefire-reports' -o -path '*/build/test-results/test' \) -type d -exec rm -rf {} + 2>/dev/null
echo "PRE=$N"
[ "${N:-0}" -ge "$MIN" ]
