#!/usr/bin/env bash
# Pre-flight for an internal VM. Does not claim a hop PASS — that needs a real repo + LLM.
#
#   ./smoke.sh              # check env, images, network, paths
#   ./smoke.sh --run        # then run BJV_MANIFEST through run.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -f "$HERE/.env" ]; then
  set -a; . "$HERE/.env"; set +a
fi

fail=0
need() {
  local n=$1
  if [ -z "${!n:-}" ]; then
    echo "MISSING $n"; fail=1
  else
    echo "ok  $n=${!n}"
  fi
}

echo "== env =="
need BJV_IMAGE
need BJV_JDK_IMAGE
need BJV_RUNROOT
need BJV_HOPTOOLS
need BJV_MANIFEST
need GIT_BASE
need OC_BASE
need OC_MODEL
need BJV_DASH_TOKEN
if [ -z "${OC_KEY:-}" ]; then
  echo "MISSING OC_KEY — Bump/Supervisor will fail closed (not a silent 'all good')"
  fail=1
else
  echo "ok  OC_KEY is set"
fi

echo "== paths =="
if [ -x "${BJV_HOPTOOLS:-}/jvm-run" ]; then
  echo "ok  hoptools/jvm-run"
else
  echo "MISSING $BJV_HOPTOOLS/jvm-run (copy current_iteration/hoptools to BJV_HOPTOOLS)"
  fail=1
fi
if [ -f "${BJV_SETTINGS:-}" ]; then
  echo "ok  settings.xml"
else
  echo "MISSING BJV_SETTINGS=${BJV_SETTINGS:-unset} (copy config/settings.xml.example)"
  fail=1
fi
if [ -d "${BJV_M2:-}" ]; then
  echo "ok  BJV_M2"
else
  echo "WARN BJV_M2=${BJV_M2:-unset} — Maven will use an empty cache inside the JDK image"
fi
if [ -d "${BJV_GRADLE_DISTS:-}" ]; then
  echo "ok  Gradle dists"
else
  echo "WARN BJV_GRADLE_DISTS unset/missing — Gradle wrapper hops will hang or fail offline"
fi

echo "== docker =="
if docker image inspect "${BJV_IMAGE:-}" >/dev/null 2>&1; then
  echo "ok  agent image $BJV_IMAGE"
else
  echo "MISSING image $BJV_IMAGE"; fail=1
fi
if docker image inspect "${BJV_JDK_IMAGE:-}" >/dev/null 2>&1; then
  echo "ok  jdk image $BJV_JDK_IMAGE"
else
  echo "MISSING image $BJV_JDK_IMAGE"; fail=1
fi
NET=${BJV_NET:-mvn-cache}
if docker network inspect "$NET" >/dev/null 2>&1; then
  echo "ok  network $NET"
else
  echo "MISSING network $NET — docker network create $NET  (attach the Maven proxy to it)"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "smoke check FAILED"
  exit 1
fi
echo "smoke check PASSED"

if [ "${1:-}" != "--run" ]; then
  echo "next: docker compose up -d"
  echo "      docker compose --profile batch run --rm launcher"
  echo "PASS means $BJV_RUNROOT/results/settlements.jsonl has a terminal PASS and the dashboard at :8086 shows the trace."
  exit 0
fi

mkdir -p "$BJV_RUNROOT/results" "$BJV_RUNROOT/ws"
exec "$HERE/run.sh" "$BJV_MANIFEST"
