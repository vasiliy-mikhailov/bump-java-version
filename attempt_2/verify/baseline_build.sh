#!/usr/bin/env bash
# Per-repo baseline build inside the runner Docker container.
# Inputs (env vars): REPO_FULL_NAME, REPO_URL, COMMIT_SHA, JAVA_VERSION
# Output: attempt_2/verify/baseline/<owner__repo>/{metrics.json,run.log}
set -uo pipefail

OUT=attempt_2/verify/baseline/${REPO_FULL_NAME//\//__}
mkdir -p "$OUT"
SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT

LOG="$OUT/run.log"
exec > "$LOG" 2>&1
echo "== $REPO_FULL_NAME @ $COMMIT_SHA on Java $JAVA_VERSION =="

t0=$(date +%s)
git clone --depth 1 --no-tags "$REPO_URL" "$SRC" || { echo CLONE_FAIL; exit 2; }
if [ -n "${COMMIT_SHA:-}" ] && [ "$COMMIT_SHA" != "HEAD" ]; then
  (cd "$SRC" && git fetch --depth=1 origin "$COMMIT_SHA" && git checkout "$COMMIT_SHA") || true
fi
CLONE_ELAPSED=$(( $(date +%s) - t0 ))
SHA=$(cd "$SRC" && git rev-parse HEAD 2>/dev/null || echo UNKNOWN)

ROOT="$SRC"
if [ ! -f "$ROOT/pom.xml" ] && [ ! -f "$ROOT/build.gradle" ] && [ ! -f "$ROOT/build.gradle.kts" ]; then
  ROOT=$(find "$SRC" -maxdepth 3 -name pom.xml -printf '%h\n' 2>/dev/null | head -1)
  [ -z "$ROOT" ] && { echo NO_BUILD_FILE; exit 3; }
fi

BUILD_TOOL=maven
[ -f "$ROOT/pom.xml" ] || BUILD_TOOL=gradle

DOCKER_IMG=java-multi-jdk-runner:latest
docker images $DOCKER_IMG --format '{{.Repository}}' | grep -q . || { echo NO_DOCKER_IMG; exit 4; }

JDK_PATH=/opt/jdk/$JAVA_VERSION
t1=$(date +%s)
timeout 600 docker run --rm \
  -v "$ROOT:/work" \
  -v "$HOME/.m2-fitness:/root/.m2" \
  -e JAVA_HOME=$JDK_PATH \
  -e PATH=$JDK_PATH/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  -w /work \
  $DOCKER_IMG \
  bash -c "if [ -f pom.xml ]; then mvn -B -fae -Denforcer.skip=true -DskipTests -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dspring-javaformat.skip=true -Dformat.skip=true compile; else ./gradlew --no-daemon -x test compileJava || gradle --no-daemon -x test compileJava; fi"
RC=$?
BUILD_ELAPSED=$(( $(date +%s) - t1 ))

cat > "$OUT/metrics.json" << JSON
{
  "repo": "$REPO_FULL_NAME",
  "commit_sha": "$SHA",
  "java_version": $JAVA_VERSION,
  "build_tool": "$BUILD_TOOL",
  "clone_elapsed_s": $CLONE_ELAPSED,
  "build_elapsed_s": $BUILD_ELAPSED,
  "build_rc": $RC,
  "build_pass": $([ $RC -eq 0 ] && echo true || echo false)
}
JSON
echo "DONE rc=$RC build=${BUILD_ELAPSED}s clone=${CLONE_ELAPSED}s sha=$SHA"
