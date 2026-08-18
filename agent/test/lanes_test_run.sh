#!/usr/bin/env bash
# Host entry point for the lane-occupancy tests.
#
# THE TEST NEVER TOUCHES THE RUNNING SWEEP. It copies run.sh into a scratch tree and runs the copy
# inside a debian container with no docker socket and no network: the `docker` on its PATH is the
# shim in bin/, so the worst a broken test can do is fill a directory under /tmp.
#
#   ./lanes_test_run.sh                 # all scenarios against ../run.sh
#   ./lanes_test_run.sh idle            # one scenario
#   RUN_SH=/path/to/candidate.sh ./lanes_test_run.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${RUN_SH:-$HERE/../run.sh}
[ -f "$SRC" ] || { echo "no run.sh at $SRC"; exit 2; }
SCRATCH=${LANETEST_SCRATCH:-/tmp/bjv-lanetest}

rm -rf "$SCRATCH"
mkdir -p "$SCRATCH/bin"
cp "$SRC" "$SCRATCH/run.sh"                 # a copy, so the live file is only ever read
cp "$HERE/lanes_test.sh" "$SCRATCH/"
cp "$HERE/docker" "$HERE/git" "$SCRATCH/bin/"
chmod +x "$SCRATCH/bin/docker" "$SCRATCH/bin/git" "$SCRATCH/run.sh"

echo "testing $SRC (copied to $SCRATCH/run.sh)"
exec docker run --rm --network none \
  --user "$(id -u):$(id -g)" \
  -v "$SCRATCH:/t" -w /t -e HOME=/t \
  debian:bookworm-slim bash /t/lanes_test.sh "$@"
