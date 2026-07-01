#!/bin/bash
# rung-2 container entrypoint: run OpenHands+Qwen on the mounted /work project under the new discipline.
# Args: FROM TO. Mounts expected: /work (project), /oh_run.py, /r2bin (verbs), /skill.md (per-hop SKILL.md).
set -uo pipefail
FROM=$1; TO=$2
export BJV_FROM="$FROM"
if [ "$TO" = 25 ]; then export BJV_REWRITE_PLUGIN=6.41.0 BJV_REWRITE_MIGRATE=3.36.0 BJV_REWRITE_DEPS=1.55.3; fi
export PATH=/r2bin:$PATH
export OC_BASE="${OC_BASE:-https://inference.mikhailov.tech/qwen-3.6-27b-fp8/v1}"
export OC_MODEL="${OC_MODEL:-qwen-3.6-27b-fp8}"
# gradle: shared RO dep cache + dists (mirror the host bjv setup) if mounted
[ -d /ro ] && export GRADLE_RO_DEP_CACHE=/ro
[ -d /dists ] && { mkdir -p "$HOME/.gradle/wrapper"; ln -sfn /dists "$HOME/.gradle/wrapper/dists"; }
mkdir -p "$HOME/.gradle"
grep -q installations.paths "$HOME/.gradle/gradle.properties" 2>/dev/null || \
  echo 'org.gradle.java.installations.paths=/opt/jdk/8,/opt/jdk/11,/opt/jdk/17,/opt/jdk/21' >> "$HOME/.gradle/gradle.properties"
cd /work
chmod +x ./gradlew 2>/dev/null || true
# never let git block on an interactive pager (a `git diff`/`log` opening `less` hangs the terminal forever)
export GIT_PAGER=cat PAGER=cat
git config --global core.pager cat 2>/dev/null || true
# Per-hop SKILL.md is the single source of truth (portable, standard-tools). The harness prepends a small
# Stage header with the facts the skill leaves generic (P3 stage-header pattern), and strips the YAML frontmatter.
if [ "${BJV_MODE:-single}" = multihop ]; then
  # per-module iteration executor: the host already rendered the per-module plan + every relevant hop skill.
  PROMPT="$(cat /prompt.txt)
Convenience shortcuts on PATH: bbuild N = compile under /opt/jdk/N; btest N = run tests under /opt/jdk/N."
else
  SKILL="$(awk 'NR==1&&/^---/{f=1;next} f&&/^---/{f=0;next} !f' /skill.md)"
  STAGE="STAGE -- migrate the project in /work from Java $FROM to Java $TO. JDK $FROM is at /opt/jdk/$FROM and JDK $TO at /opt/jdk/$TO (use these for <jdk$FROM>/<jdk$TO> in the skill). Convenience shortcuts on PATH, equivalent to the skill's standard commands: bbuild N = compile under /opt/jdk/N; btest N = run tests under /opt/jdk/N; bapply = apply ./rewrite.yml with this hop's OpenRewrite coordinates. Builds are NOT time-boxed -- let cold builds finish, never abandon a running build. Now follow this skill:"
  PROMPT="$STAGE
$SKILL"
fi
# vLLM TCP keepalive: litellm's aiohttp transport gates SO_KEEPALIVE on these (default OFF). Our topology is
# agent -> caddy -> shared busy vLLM; without keepalive a slow-inference idle period gets reaped by the proxy/NAT
# hop into a stalled socket (then 300s wasted before the request timeout fires). KEEPIDLE 60 + KEEPINTVL 30.
export AIOHTTP_SO_KEEPALIVE=true AIOHTTP_TCP_KEEPIDLE=60 AIOHTTP_TCP_KEEPINTVL=30
/opt/ohvenv/bin/python /oh_run.py /work "$PROMPT"
