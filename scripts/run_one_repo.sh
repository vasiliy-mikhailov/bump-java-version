#!/usr/bin/env bash
# Per-repo fitness evaluation. Invoked as the container entrypoint.
#
# Environment contract (all set by the orchestrator):
#   REPO_URL         git clone URL
#   REPO_SHA         commit to check out
#   REPO_ID          dataset id (e.g. sb2-j8-1)
#   JAVA_VERSION     8 | 11 | 17  (source JDK; we still build with 21 toolchain
#                                  after the recipe runs, since the goal is Java 21)
#   BUILD_TOOL       maven | gradle
#   RECIPE_NAME      composite recipe name, e.g. com.fitness.candidate.MigrateToJava21
#   RECIPE_YML_PATH  /work/rewrite.yml (mounted from orchestrator)
#   OUT_DIR          /out  (mounted; we write metrics.json here)
#
# Strategy:
#   1. clone @ SHA (shallow, fast).
#   2. record pre-recipe build status (a known-good baseline before any tampering).
#   3. inject OpenRewrite plugin and run the composite recipe.
#   4. attempt build + tests under Java 21 toolchain.
#   5. emit metrics.json with the fields the scorer expects.

set -uo pipefail

require() { [ -n "${!1:-}" ] || { echo "missing env $1" >&2; exit 64; }; }
for v in REPO_URL REPO_SHA REPO_ID JAVA_VERSION BUILD_TOOL RECIPE_NAME RECIPE_YML_PATH OUT_DIR; do
  require "$v"
done

mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/run.log"
METRICS="$OUT_DIR/metrics.json"
: > "$LOG"

log()   { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG" >&2; }
emit()  { jq -n "$1" > "$METRICS"; }

# Defaults — overwritten as each phase completes.
build_pre=0; build_post=0; tests_pre=0; tests_post=0; tests_total_post=0
recipe_applied=0; diff_files=0; diff_binary_files=0
recipe_elapsed=0; build_elapsed=0; test_elapsed=0
phase="init"; failure=""

write_metrics() {
  emit "{
    repo_id: \"$REPO_ID\",
    repo_url: \"$REPO_URL\",
    sha: \"$REPO_SHA\",
    java_version: $JAVA_VERSION,
    build_tool: \"$BUILD_TOOL\",
    phase_reached: \"$phase\",
    failure: \"$failure\",
    build_pre: $build_pre,
    build_post: $build_post,
    tests_passed_post: $tests_post,
    tests_total_post: $tests_total_post,
    recipe_applied: $recipe_applied,
    diff_files: $diff_files,
    diff_binary_files: $diff_binary_files,
    recipe_elapsed_s: $recipe_elapsed,
    build_elapsed_s: $build_elapsed,
    test_elapsed_s: $test_elapsed
  }"
}
trap write_metrics EXIT

source /opt/sdkman/bin/sdkman-init.sh

# Source JDK (used for the pre-recipe baseline build only — many repos
# won't compile on Java 21 yet, so we baseline on their declared JDK).
case "$JAVA_VERSION" in
  8)  sdk use java 8.0.422-tem  ;;
  11) sdk use java 11.0.24-tem ;;
  17) sdk use java 17.0.12-tem ;;
  21) sdk use java 21.0.4-tem  ;;
  *) echo "unknown JAVA_VERSION=$JAVA_VERSION" >&2; failure="bad-java"; exit 65 ;;
esac

phase="clone"
log "cloning $REPO_URL @ $REPO_SHA"
git clone --filter=blob:none --no-checkout "$REPO_URL" /work/src >>"$LOG" 2>&1 || { failure="clone"; exit 1; }
cd /work/src
git fetch --depth 50 origin "$REPO_SHA" >>"$LOG" 2>&1 || true
git checkout --detach "$REPO_SHA" >>"$LOG" 2>&1 || { failure="checkout"; exit 1; }

phase="baseline"
log "baseline build (source JDK $JAVA_VERSION)"
t0=$(date +%s)
if [ "$BUILD_TOOL" = "maven" ]; then
  if mvn -B -q -DskipTests -ntp package >>"$LOG" 2>&1; then build_pre=1; fi
else
  if ./gradlew --no-daemon -q assemble >>"$LOG" 2>&1; then build_pre=1; fi
fi
log "baseline build_pre=$build_pre (${build_pre}/1)"

# Copy in the candidate rewrite.yml. The orchestrator already wrapped
# the active-recipes list into a single composite named $RECIPE_NAME.
cp "$RECIPE_YML_PATH" /work/src/rewrite.yml

phase="recipe"
log "running recipe $RECIPE_NAME"
t0=$(date +%s)
if [ "$BUILD_TOOL" = "maven" ]; then
  mvn -B -ntp -U \
      org.openrewrite.maven:rewrite-maven-plugin:5.43.0:run \
      -Drewrite.activeRecipes="$RECIPE_NAME" \
      -Drewrite.configLocation=/work/src/rewrite.yml \
      -Drewrite.exportDatatables=false \
      -Drewrite.failOnInvalidActiveRecipes=true \
      >>"$LOG" 2>&1
  rc=$?
else
  cat >/tmp/rewrite-init.gradle <<'GRADLE'
initscript {
  repositories { mavenCentral() }
  dependencies { classpath "org.openrewrite:plugin:6.24.0" }
}
allprojects {
  apply plugin: org.openrewrite.gradle.RewritePlugin
  dependencies {
    rewrite "org.openrewrite.recipe:rewrite-migrate-java:latest.release"
    rewrite "org.openrewrite.recipe:rewrite-spring:latest.release"
    rewrite "org.openrewrite.recipe:rewrite-testing-frameworks:latest.release"
    rewrite "org.openrewrite.recipe:rewrite-hibernate:latest.release"
  }
  rewrite {
    activeRecipe(System.getenv("RECIPE_NAME"))
    configFile = file("/work/src/rewrite.yml")
  }
}
GRADLE
  ./gradlew --no-daemon -I /tmp/rewrite-init.gradle rewriteRun >>"$LOG" 2>&1
  rc=$?
fi
recipe_elapsed=$(( $(date +%s) - t0 ))
log "recipe rc=$rc, elapsed=${recipe_elapsed}s"
if [ $rc -ne 0 ]; then failure="recipe"; fi

phase="diff-stat"
diff_files=$(git status --porcelain | wc -l | tr -d ' ')
# binary-ish files OpenRewrite should never touch; if it did, score it down.
diff_binary_files=$(git status --porcelain | awk '{print $2}' | grep -cE '\.(class|jar|war|png|jpg|jpeg|gif|so|dll|dylib)$' || true)
if [ "$diff_files" -gt 0 ]; then recipe_applied=1; fi
log "diff_files=$diff_files diff_binary_files=$diff_binary_files"

# Post-recipe build/test on Java 21 — the migration target.
sdk use java 21.0.4-tem

phase="post-build"
t0=$(date +%s)
if [ "$BUILD_TOOL" = "maven" ]; then
  if mvn -B -q -DskipTests -ntp -Djava.version=21 -Dmaven.compiler.release=21 package >>"$LOG" 2>&1; then build_post=1; fi
else
  if ./gradlew --no-daemon -q -Porg.gradle.java.installations.auto-detect=false assemble >>"$LOG" 2>&1; then build_post=1; fi
fi
build_elapsed=$(( $(date +%s) - t0 ))
log "build_post=$build_post elapsed=${build_elapsed}s"

if [ "$build_post" -ne 1 ]; then failure="${failure:-build-post}"; exit 0; fi

phase="post-test"
t0=$(date +%s)
if [ "$BUILD_TOOL" = "maven" ]; then
  mvn -B -ntp -Djava.version=21 -Dmaven.compiler.release=21 \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dsurefire.useFile=false \
      test >>"$LOG" 2>&1 || true
  # parse surefire output for pass/total
  read -r passed total < <(grep -E '^\[INFO\] Tests run:' "$LOG" | tail -1 | \
    awk '{
      run=0; fail=0; err=0; skip=0;
      for (i=1;i<=NF;i++) {
        if ($i=="Tests" && $(i+1)=="run:") { run=$(i+2)+0 }
        if ($i=="Failures:") { fail=$(i+1)+0 }
        if ($i=="Errors:") { err=$(i+1)+0 }
        if ($i=="Skipped:") { skip=$(i+1)+0 }
      }
      print (run-fail-err-skip), run
    }')
  tests_post=${passed:-0}; tests_total_post=${total:-0}
else
  ./gradlew --no-daemon test >>"$LOG" 2>&1 || true
  # crude Gradle parse — count xml test reports
  passed=0; total=0
  while IFS= read -r f; do
    p=$(grep -oE 'tests="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+' || echo 0)
    fl=$(grep -oE 'failures="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+' || echo 0)
    er=$(grep -oE 'errors="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+' || echo 0)
    sk=$(grep -oE 'skipped="[0-9]+"' "$f" | head -1 | grep -oE '[0-9]+' || echo 0)
    total=$(( total + p ))
    passed=$(( passed + p - fl - er - sk ))
  done < <(find . -path '*/build/test-results/*.xml' -type f 2>/dev/null)
  tests_post=${passed:-0}; tests_total_post=${total:-0}
fi
test_elapsed=$(( $(date +%s) - t0 ))
log "tests $tests_post / $tests_total_post elapsed=${test_elapsed}s"

phase="done"
exit 0
