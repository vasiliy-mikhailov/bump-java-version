#!/bin/bash
# v2 end-to-end (single-shot): clone -> baseline -> generate_program (LLM, per-hop skill) -> check_program
# -> apply program -> combined gate -> score.  REPO SHA FROM TO SLUG ; env: OC_BASE OC_MODEL OC_KEY
set -uo pipefail
REPO=$1; SHA=$2; FROM=$3; TO=$4; SLUG=$5
ITER=/home/vmihaylov/java_8_11_17_to_java_21/current_attempt/current_iteration
SK=/home/vmihaylov/java_8_11_17_to_java_21/current_attempt/.agents/skills/bump-java-$FROM-to-$TO/SKILL.md
export PATH="$ITER/hoptools:$PATH"
WS=/tmp/bjv_ws/$SLUG
OUT=${OUT_DIR:-$ITER/out}/$SLUG; mkdir -p "$OUT"
PROG="$OUT/program.json"
export BJV_WS="$WS" BJV_FROM="$FROM" BJV_TO="$TO" BJV_NET=mvn-cache \
  BJV_M2=/home/vmihaylov/.m2-fitness BJV_SETTINGS=/home/vmihaylov/maven-config/settings.xml \
  BJV_GRADLE_RO=/home/vmihaylov/.gradle-fitness BJV_GRADLE_DISTS=/home/vmihaylov/.gradle-dists
PY() { docker run --rm -v "$WS:$WS" -v "$OUT:$OUT" -v "$ITER/tools:/t:ro" -v "$ITER/catalog:/cat:ro" python:3-slim python3 "$@"; }
emit() { printf '{\n "slug":"%s",\n "hop":"%s->%s",\n "verdict":"%s"\n}\n' "$SLUG" "$FROM" "$TO" "$1" > "$OUT/result.json"; }

# 1. workspace
docker run --rm -v /tmp/bjv_ws:/wsroot alpine rm -rf "/wsroot/$SLUG" 2>/dev/null || true
mkdir -p "$WS"
git clone -q "https://github.com/$REPO.git" "$WS" 2>>"$OUT/clone.log" || { emit FETCH_FAIL; exit 0; }
git -C "$WS" checkout -q "$SHA" 2>>"$OUT/clone.log" || { emit FETCH_FAIL; exit 0; }
BT=gradle; [ -f "$WS/pom.xml" ] && BT=mvn

# 2. baseline under sealed jv_from
bjv from build > "$OUT/pre_build.log" 2>&1 || { emit NO_BASELINE_NOCOMPILE; exit 0; }
bjv from test  > "$OUT/pre_test.log"  2>&1 || true
PRE=$(PY /t/score.py passet "$WS" "$OUT/pre_set.txt" 2>/dev/null | tail -1); PRE=${PRE:-0}
find "$WS" \( -path '*/target/surefire-reports' -o -path '*/build/test-results/test' \) -type d -exec rm -rf {} + 2>/dev/null || true
[ "$PRE" = 0 ] && { emit NO_BASELINE_NOTESTS; exit 0; }

# 3. generate_program (LLM, per-hop skill) -> program.json
docker run --rm --network mvn-cache -e OC_BASE -e OC_MODEL -e OC_KEY \
  -v "$WS:$WS" -v "$ITER/tools:/t:ro" -v "$SK:/skill.md:ro" python:3-slim \
  python3 /t/generate_program.py /skill.md "$WS" "$FROM" "$TO" > "$PROG" 2>"$OUT/generate.log" \
  || { emit FAIL_GENERATE; echo "generate failed"; tail -3 "$OUT/generate.log"; exit 0; }

# 4. static anti-cheat
CHK=$(docker run --rm -v "$OUT:$OUT" -v "$ITER/tools:/t:ro" -v "$ITER/catalog:/cat:ro" python:3-slim python3 /t/check_program.py "$PROG" /cat/recipes.txt 2>&1)
echo "$CHK" > "$OUT/check.log"; [ "$CHK" = OK ] || { emit FAIL_CHEAT; echo "check: $CHK"; exit 0; }

# 5. apply the generated program
PY /t/applyprog.py "$PROG" "$FROM" "$TO" "$BT" > "$OUT/ops.tsv" 2>"$OUT/apply.log"
n=0; while IFS=$'\t' read -r env cmd; do
  [ -z "$env" ] && continue
  n=$((n+1)); echo "=== op $n [$env] $cmd" >> "$OUT/apply.log"
  bjv "$env" run "$cmd" >> "$OUT/apply.log" 2>&1 || echo "(op $n rc=$?)" >> "$OUT/apply.log"
done < "$OUT/ops.tsv"

# 6. combined gate
bjv to build > "$OUT/compile.log" 2>&1; COMPRC=$?
bjv to test  > "$OUT/post.log"    2>&1; POSTRC=$?
bjv to run "osv-scanner scan source --offline-vulnerabilities -r . --format json" > "$OUT/cwe.json" 2>/dev/null || true
PY /t/score.py final "$WS" "$OUT/pre_set.txt" "$FROM" "$TO" "$COMPRC" "$POSTRC" "$OUT/cwe.json" "$OUT"
