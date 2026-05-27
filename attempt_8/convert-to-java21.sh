#!/usr/bin/env bash
#
# convert-to-java21.sh — convert ONE Maven project to Java 21 using OpenRewrite.
#
# Distilled from the java_8_11_17_to_java_21 project (attempt_8). Pinned to the
# exact plugin + recipe artifact versions proven against a 222-stage corpus.
# Expected PASS rate on a broad corpus with this minimal chain only: ~58%.
# Good for spot-checks and well-behaved Maven projects (especially Java 17 → 21).
#
# ─────────────────────────────────────────────────────────────────────────────
# USAGE
# ─────────────────────────────────────────────────────────────────────────────
#
#   convert-to-java21.sh <repo> <sha-or-branch> <jv_from> <work-dir>
#
#     <repo>       Git URL or absolute local path to a Maven project.
#     <sha>        Commit SHA or branch name. Use 'HEAD' for local paths.
#     <jv_from>    Current Java version. One of: 8, 11, 17.
#     <work-dir>   Empty/non-existing scratch directory. Will be created.
#
# Examples:
#     convert-to-java21.sh https://github.com/spring-projects/spring-petclinic main 17 /tmp/conv1
#     convert-to-java21.sh /home/me/my-project HEAD 17 /tmp/conv2
#
# ─────────────────────────────────────────────────────────────────────────────
# PREREQUISITES
# ─────────────────────────────────────────────────────────────────────────────
#
# On PATH: git, mvn, java.
# JDK env vars (only those needed by jv_from..jv_to=21):
#     export JAVA_8_HOME=/path/to/jdk-8           # if jv_from=8
#     export JAVA_11_HOME=/path/to/jdk-11         # if jv_from<=11
#     export JAVA_17_HOME=/path/to/jdk-17         # always
#     export JAVA_21_HOME=/path/to/jdk-21         # always
#
# Debian/Ubuntu:
#     sudo apt install openjdk-{8,11,17,21}-jdk maven
#     export JAVA_8_HOME=/usr/lib/jvm/java-8-openjdk-amd64   # etc.
#
# macOS:
#     brew install openjdk@8 openjdk@11 openjdk@17 openjdk@21 maven
#     export JAVA_8_HOME=$(/usr/libexec/java_home -v 1.8)   # etc.
#
# Internet (or a pre-warmed Maven mirror) needed for OpenRewrite + Spring artifacts.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT THIS DOES (chain — shorter for higher jv_from)
# ─────────────────────────────────────────────────────────────────────────────
#
#   1. lombok_safe_bump        @ jdk≥11   Lombok → 1.18.36 (covers JDK 17+21 module access)
#   2. java8_to_java11         @ jdk=11   (if jv_from==8) — Java 8→11 source migration
#   3. plugins_for_java17      @ jdk=11   Bump maven-* plugins to J17-compatible versions
#   4. build_to_java17         @ jdk=17   Set source/target to 17
#   5. java17_transforms       @ jdk=17   InstanceOfPatternMatch, etc.
#   6. plugins_for_java21      @ jdk=17   Bump plugins to J21-compatible
#   7. build_to_java21         @ jdk=21   Set source/target to 21
#   8. java21_transforms       @ jdk=21   SequencedCollection, ThreadStopUnsupported, etc.
#
# After each step:  mvn compile  to verify post-recipe builds. Fails fast on any error.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT THIS DOES NOT DO (kept minimal)
# ─────────────────────────────────────────────────────────────────────────────
#
#   - Pom-level feature detection (so no conditional steps).
#   - Spring Boot stepped upgrade (no SB 2.x → 2.7 → 3.0 → 3.2 trajectory).
#   - JAXB prep / WebSecurityConfigurerAdapter migration / Hibernate 5→6 @Type fix.
#   - Test conservation (no pre/post mvn test check).
#   - LLM rescue on failure.
#
# To reach beyond ~58% PASS on heterogeneous corpora, extend the chain or wrap
# this in the python harness in attempt_7/tools/. See README.md.
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# help / no-args
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 4 ]]; then
  awk 'NR==1{next} /^[^#]/{exit} /^#/{sub(/^# ?/,""); print}' "$0"
  echo
  exit 1
fi

REPO="$1"; SHA="$2"; JV_FROM="$3"; WORK="$4"

# prereq checks
need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "missing on PATH: $1" >&2; exit 2; }; }
need_cmd git; need_cmd mvn; need_cmd java
case "$JV_FROM" in 8|11|17) ;; *) echo "jv_from must be in {8,11,17}; got: $JV_FROM" >&2; exit 2;; esac
need_jhome() {
  local v="JAVA_$1_HOME"
  [[ -n "${!v:-}" && -x "${!v}/bin/javac" ]] || { echo "$v not set or invalid" >&2; exit 2; }
}
[[ "$JV_FROM" -le 8  ]] && need_jhome 8
[[ "$JV_FROM" -le 11 ]] && need_jhome 11
need_jhome 17; need_jhome 21
if [[ -e "$WORK" && -n "$(ls -A "$WORK" 2>/dev/null)" ]]; then
  echo "work-dir is not empty: $WORK   (refuse to clobber)" >&2; exit 2
fi
mkdir -p "$WORK/src" "$WORK/recipes"

# pinned versions (proven by attempt_8 against 222-stage corpus)
PLUGIN="org.openrewrite.maven:rewrite-maven-plugin:6.40.0"
COORDS="org.openrewrite.recipe:rewrite-migrate-java:3.35.0,org.openrewrite.recipe:rewrite-spring:6.31.0,org.openrewrite.recipe:rewrite-testing-frameworks:3.36.0,org.openrewrite.recipe:rewrite-hibernate:2.20.3"
MVN_FLAGS="-B -ntp -fae -Denforcer.skip=true -DskipTests -Dlombok.version=1.18.36 -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dspring-boot.repackage.skip=true -Dspring-javaformat.skip=true"

# clone or copy
if [[ -d "$REPO" ]]; then
  echo "==> copying local project from $REPO"
  cp -r "$REPO"/. "$WORK/src/"
  cd "$WORK/src"
  if [[ "$SHA" != "HEAD" && -d .git ]]; then git checkout -q "$SHA"; fi
else
  echo "==> shallow-cloning $REPO @ $SHA"
  cd "$WORK/src"
  git init -q
  git remote add origin "$REPO"
  git fetch --depth=1 origin "$SHA"
  git checkout -q FETCH_HEAD
fi
echo

# emit a parameterized recipe yaml in proper block style
write_recipe_simple() {  # $1=label, $2..=FQN recipes
  local f="$WORK/recipes/$1.yml"
  { echo "---"; echo "type: specs.openrewrite.org/v1beta/recipe"
    echo "name: local.$1"; echo "recipeList:"
    shift; for r in "$@"; do echo "  - $r"; done
  } > "$f"
}

# run a recipe file + post-compile check
run_step() {  # $1=label $2=jdk
  local label="$1" jdk="$2"
  local rfile="$WORK/recipes/$label.yml"
  local jhv="JAVA_${jdk}_HOME"
  local jh="${!jhv}"
  echo "==> $label  (jdk=$jdk)"
  JAVA_HOME="$jh" PATH="$jh/bin:$PATH" \
    mvn $MVN_FLAGS -U "$PLUGIN:run" \
      -Drewrite.activeRecipes="local.$label" \
      -Drewrite.configLocation="$rfile" \
      -Drewrite.recipeArtifactCoordinates="$COORDS" \
      -Drewrite.failOnInvalidActiveRecipes=true
  local rel
  if [[ "$jdk" -lt 9 ]]; then
    rel="-Dmaven.compiler.source=$jdk -Dmaven.compiler.target=$jdk"
  else
    rel="-Dmaven.compiler.release=$jdk"
  fi
  JAVA_HOME="$jh" PATH="$jh/bin:$PATH" \
    mvn $MVN_FLAGS $rel -Djava.version=$jdk -q compile
  echo "    ok"; echo
}

# ─── chain ────────────────────────────────────────────────────────────────────
LJDK=$([ "$JV_FROM" -lt 11 ] && echo 11 || echo "$JV_FROM")

# 1. lombok (parameterized → write the yaml directly, then run)
cat > "$WORK/recipes/lombok_safe_bump.yml" <<EOF
---
type: specs.openrewrite.org/v1beta/recipe
name: local.lombok_safe_bump
recipeList:
  - org.openrewrite.maven.UpgradeDependencyVersion:
      groupId: org.projectlombok
      artifactId: lombok
      newVersion: 1.18.36
  - org.openrewrite.maven.ChangePropertyValue:
      key: lombok.version
      newValue: 1.18.36
  - org.openrewrite.maven.ChangePropertyValue:
      key: org.projectlombok.lombok.version
      newValue: 1.18.36
  - org.openrewrite.maven.ChangePropertyValue:
      key: lombokVersion
      newValue: 1.18.36
EOF
run_step lombok_safe_bump "$LJDK"

# 2. java8→java11 (only if jv_from=8)
if [[ "$JV_FROM" -le 8 ]]; then
  write_recipe_simple java8_to_java11 org.openrewrite.java.migrate.Java8toJava11
  run_step java8_to_java11 11
fi

# 3-5. Java 11→17 trajectory
if [[ "$JV_FROM" -le 11 ]]; then
  write_recipe_simple plugins_for_java17 org.openrewrite.java.migrate.UpgradePluginsForJava17
  run_step plugins_for_java17 11
  write_recipe_simple build_to_java17 org.openrewrite.java.migrate.UpgradeBuildToJava17
  run_step build_to_java17 17
  write_recipe_simple java17_transforms \
    org.openrewrite.staticanalysis.InstanceOfPatternMatch \
    org.openrewrite.staticanalysis.AddSerialAnnotationToSerialVersionUID \
    org.openrewrite.java.migrate.RemovedToolProviderConstructor \
    org.openrewrite.java.migrate.RemovedModifierAndConstantBootstrapsConstructors \
    org.openrewrite.java.migrate.lang.ExplicitRecordImport
  run_step java17_transforms 17
fi

# 6-8. Java 17→21 trajectory
write_recipe_simple plugins_for_java21 org.openrewrite.java.migrate.UpgradePluginsForJava21
run_step plugins_for_java21 17
write_recipe_simple build_to_java21 org.openrewrite.java.migrate.UpgradeBuildToJava21
run_step build_to_java21 21
write_recipe_simple java21_transforms \
  org.openrewrite.java.migrate.RemoveIllegalSemicolons \
  org.openrewrite.java.migrate.lang.ThreadStopUnsupported \
  org.openrewrite.java.migrate.net.URLConstructorToURICreate \
  org.openrewrite.java.migrate.util.SequencedCollection \
  org.openrewrite.java.migrate.util.UseLocaleOf \
  org.openrewrite.staticanalysis.ReplaceDeprecatedRuntimeExecMethods \
  org.openrewrite.java.migrate.DeleteDeprecatedFinalize
run_step java21_transforms 21

echo "================================================================"
echo " PASS — converted tree at $WORK/src"
echo "================================================================"
echo " Inspect changes:   git -C $WORK/src diff $SHA"
echo " Test under J21:    cd $WORK/src && JAVA_HOME=\$JAVA_21_HOME mvn test"
