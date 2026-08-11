#!/usr/bin/env bash
# The deterministic migration, as one script the Java chain calls and traces.
# Content lives here (recipes, floors, target propagation) so Bump.java can stay THE ORDER
# and never grow opinions about Maven. Args: <ws> <from> <to>
set -uo pipefail
ws=$1; from=$2; to=$3
I=/home/vmihaylov/bump-java-version/current_attempt/current_iteration
S=/home/vmihaylov/trivy-stage
export BJV_WS=$ws BJV_NET=mvn-cache BJV_M2=/home/vmihaylov/.m2-fitness \
  BJV_SETTINGS=/home/vmihaylov/maven-config/settings.xml \
  BJV_GRADLE_RO=/home/vmihaylov/.gradle-fitness BJV_GRADLE_DISTS=/home/vmihaylov/.gradle-dists

# the Spring line, by what the project is on, never by the target JDK
SR=$(docker run --rm -v "$ws:/w:ro" -v "$S:/s:ro" python:3-slim \
  python3 /s/bootline.py /w 2>/dev/null | tail -1)
case "$SR" in
  2) SPRING="org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7";;
  3) SPRING="org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5";;
  *) SPRING="";;
esac

{ echo "type: specs.openrewrite.org/v1beta/recipe"
  echo "name: com.bjv.Bump"
  echo "recipeList:"
  echo "  - org.openrewrite.java.migrate.UpgradePluginsForJava${to}"
  echo "  - org.openrewrite.java.migrate.UpgradeBuildToJava${to}"
  echo "  - org.openrewrite.java.migrate.UpgradeJavaVersion:"
  echo "      version: ${to}"
  echo "  - org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo"
  [ "$to" = 11 ] && echo "  - org.openrewrite.java.migrate.Java8toJava11"
  [ -n "$SPRING" ] && echo "  - $SPRING"
} > "$ws/rewrite.yml"
echo "recipes: $(grep -c '^  - ' "$ws/rewrite.yml") (spring: ${SPRING:-none})"

if [ -f "$ws/pom.xml" ]; then
  timeout -k 60 2700 "$I/hoptools/jvm-run" "$from" jvmjob run \
    "mvn -B -ntp -U -Denforcer.skip=true org.openrewrite.maven:rewrite-maven-plugin:6.40.0:run -Drewrite.configLocation=\$(pwd)/rewrite.yml -Drewrite.activeRecipes=com.bjv.Bump -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-migrate-java:3.36.0,org.openrewrite.recipe:rewrite-spring:6.31.0" \
    2>&1 | tail -5
  echo "recipe rc=$?"
  docker run --rm -v "$ws:/w" -v /dev/null:/o/none:ro -v "$S:/s:ro" python:3-slim \
    python3 /s/floors.py /w /o/none "$to" 2>&1
  docker run --rm -v "$ws:/w" -v "$S:/s:ro" python:3-slim \
    python3 /s/targetprop.py /w "$to" 2>&1
fi
