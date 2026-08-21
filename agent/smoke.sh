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

# WHAT THE SETTINGS PAGE SAVED COUNTS AS SET NOW. run.sh reads that store once per lane, with the
# environment underneath, so a check that looked only at the environment would report a sweep as
# unlaunchable while the page already has everything it needs. Parsed, never sourced, for the same
# reason the launcher parses it: the file is written by a page on the public internet.
SETTINGS=${BJV_MODEL_SETTINGS:-${BJV_RUNROOT:-}/model}
saved() {
  [ -f "$SETTINGS" ] && [ -r "$SETTINGS" ] || return 0
  sed -n "s/^$1=//p" "$SETTINGS" 2>/dev/null | tail -1
}

either() {                        # either <ENV_NAME> <name-in-the-store>
  local n=$1 s
  s=$(saved "$2")
  if [ -n "${!n:-}" ]; then
    echo "ok  $n=${!n}"
  elif [ -n "$s" ]; then
    echo "ok  $2=$s (saved on the settings page)"
  else
    echo "MISSING $n, and nothing named $2 is saved on the settings page"; fail=1
  fi
}

echo "== env =="
need BJV_IMAGE
need BJV_JDK_IMAGE
need BJV_RUNROOT
need BJV_HOPTOOLS
need BJV_MANIFEST
need GIT_BASE
either OC_BASE endpoint
either OC_MODEL model
need BJV_DASH_TOKEN
# WHETHER, NEVER WHICH. The key is the one value here that is never echoed, and what matters is only
# that a lane will be opened at all: run.sh refuses to start one with no key rather than launching a
# lane that sends an empty bearer token and produces a verdict out of silence.
if [ -n "${OC_KEY:-}" ] || [ -n "$(saved key)" ] \
   || [ -s "${BJV_RUNROOT:-}/model_key" ] || [ -n "${PROPOSER_API_KEY:-}" ]; then
  echo "ok  a model key is set"
else
  echo "MISSING a model key: nothing in OC_KEY, nothing in PROPOSER_API_KEY and nothing saved on"
  echo "        the settings page. run.sh will refuse to open a lane, and the supervisor fails closed."
  fail=1
fi

echo "== paths =="
if [ -x "${BJV_HOPTOOLS:-}/jvm-run" ]; then
  echo "ok  hoptools/jvm-run"
else
  echo "MISSING $BJV_HOPTOOLS/jvm-run (copy hoptools/ to BJV_HOPTOOLS)"
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
