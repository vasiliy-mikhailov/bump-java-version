#!/usr/bin/env bash
# Build here, ship the jar, then build the image there. IN THAT ORDER, AND ALL THREE.
#
# The Dockerfile copies app/target/bump-agent-0.1.0-SNAPSHOT.jar rather than building it, so
# syncing the sources and running `docker build` does exactly nothing on its own: the COPY layer
# hits cache and the image ships the previous jar. It reports success. A live sweep ran an hour of
# already-committed-and-pushed code that way, and the trace is what caught it, not the deploy.
#
# Every dependency the jar needs is now an ordinary artifact from Central. It used to need a
# locally-installed SNAPSHOT that no build host had, which made "build the jar first" a fact about
# one machine; it is a fact about this script's order now.
#
# THAT PATH HAS A MODULE IN FRONT OF IT NOW. jvm and app are separate jars and only
# app shades, so the artifact is app/target/bump-agent-0.1.0-SNAPSHOT.jar. It is named here
# and in the Dockerfile, and those two move together or the image ships the previous jar.
set -euo pipefail
cd "$(dirname "$0")"
H=${BJV_HOST:-mh}
R=${BJV_REMOTE:-/home/vmihaylov/bump-java-version/agent}

# RUNNING ON THE TARGET IS THE NORMAL CASE NOW, and it used to be the broken one. This script was
# written to be invoked from the author's laptop, so every step goes through `ssh $H` and $H
# defaults to `mh`, which is an alias in one person's ssh config and not a name the host itself can
# resolve. With the ui building in a container, the host can do the whole job and has the checkout
# already; syncing a directory to itself over an unresolvable hostname is the only thing standing
# in the way. So: if the source IS the destination, there is nothing to ship.
HERE=$(cd "$(dirname "$0")" && pwd)
if [ "$HERE" = "$R" ]; then
  LOCAL=1
  run() { sh -c "$1"; }
else
  LOCAL=
  run() { ssh "$H" "$1"; }
fi
RESULTS=${BJV_RESULTS:-/home/vmihaylov/bump-java-version/runs/agent/results}
# THE FRONTEND FIRST, INTO THE JAR. The pages are a Next.js static export served from the
# classpath, so the container stays single-process: one JVM, no node beside it. Building it here
# rather than in the image is the same reason the jar is: the image has no toolchain, and a COPY of
# a stale bundle reports success. The export is a build output and is gitignored.
# THE UI BUILDS IN A CONTAINER, so this script runs wherever docker does.
#
# It used to need pnpm on the invoking machine. Exactly one machine had it, that machine was a
# scratch directory outside the repo, and it drifted three commits behind main without anything
# noticing. The host has the checkout, maven and docker; what it did not have was node, and
# installing a language runtime is not a thing a deploy script should assume it may do to a box.
#
# corepack reads packageManager from ui/package.json, so the pnpm version is pinned by the repo
# rather than by whatever the machine happens to carry. Runs as the invoking user so node_modules
# and the export do not come back owned by root, with HOME redirected because that user has none
# inside the image.
mkdir -p "$HOME/.cache/bjv-pnpm"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp -e COREPACK_HOME=/tmp/corepack -e CI=1 \
  -v "$PWD/ui:/ui" \
  -v "$HOME/.cache/bjv-pnpm:/pnpm-store" \
  -w /ui node:22-bookworm sh -euc '
    mkdir -p /tmp/bin /tmp/corepack
    corepack enable --install-directory /tmp/bin
    export PATH=/tmp/bin:$PATH
    pnpm config set store-dir /pnpm-store
    pnpm install --frozen-lockfile
    pnpm build'
rm -rf app/src/main/resources/ui
cp -r ui/apps/web/out app/src/main/resources/ui
# ONE REACTOR PASS, FROM THE PARENT. jvm, then app; the shade runs in app and
# nowhere else, so the only fat jar this produces is app/target/bump-agent-0.1.0-SNAPSHOT.jar.
mvn -B -o package
if [ -z "$LOCAL" ]; then
  # TWO SOURCE TREES AND THE PARENT POM. --delete keeps the far side from accumulating
  # files this checkout no longer has; target/ is excluded, and rsync protects an excluded
  # path on the receiver, so a remote build output is never deleted out from under a build.
  #
  # engine IS NOT IN THIS LIST ANY MORE, and naming a directory that no longer exists is an
  # rsync failure and not a warning. The engine is a published library now, so the far side
  # resolves it from the repository like any other dependency; the corollary is that the
  # offline package below cannot succeed until that version is in the local repository, and
  # it says so loudly rather than building something short of it.
  rsync -a --delete --exclude=target/ pom.xml jvm app "$H:$R/"
  # The jar lands in a directory that a fresh checkout does not have yet.
  run "mkdir -p $R/app/target"
  rsync -a app/target/bump-agent-0.1.0-SNAPSHOT.jar "$H:$R/app/target/"
fi
# THE DOCKERFILES TOO. They live here and are built there, so a new one is a build failure and an
# edited one is silently the old one -- the same shape as this script not shipping itself, which
# also had to be found the hard way.
if [ -z "$LOCAL" ]; then
  rsync -a Dockerfile run.sh rerun.sh deploy.sh "$H:$R/"
fi
# ONE IMAGE. The agent, the dashboard and the supervisor are the same jar with different main
# classes; three Dockerfiles copying the same artifact meant three builds and three chances for one
# of them to be a version behind.
# THE COMMIT GOES IN WITH THE BUILD. -dirty is not cosmetic here: this script packages the
# working tree, so an image built from uncommitted edits is normal and a bare sha would claim
# otherwise.
STAMP="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
# SCOPED TO WHAT GOES IN THE IMAGE. Unscoped, this repository is permanently dirty: proxy/ carries
# routing changes that are deliberately never committed and the runs_* trees are gigabytes of
# untracked sweep output. Every stamp therefore read -dirty, which is the same as no flag at all.
if [ -n "$(git status --porcelain . 2>/dev/null)" ]; then STAMP="$STAMP-dirty"; fi
run "cd $R && docker build -q --build-arg BJV_COMMIT=$STAMP -t bjv ."

# EVERY ENTRY CLASS MUST LOAD, CHECKED BEFORE ANYTHING IS RECREATED.
#
# This cost nine hours of a live sweep and about twelve hundred no-op bumps. Bump moved from
# tech.mikhailov.bjv.agent to tech.mikhailov.bjv.bump in a package split. The image was correct,
# run.sh on disk was correct, deploy.sh was correct, and every lane still died instantly with
# ClassNotFoundException, because the LAUNCHER ALREADY RUNNING had the old name parsed in memory.
# Nothing said a word: the deploy reported success and the evidence was in per-lane logs nobody
# reads.
#
# A class that cannot be loaded is the cheapest deploy failure to detect and one of the most
# expensive to miss. The names are read out of the scripts that invoke them rather than typed here,
# so this cannot drift from what is actually launched.
# RUN WITH A LEASH, BECAUSE THE IMAGE IS A JRE. Two earlier versions of this check were wrong in
# opposite ways: the first ran each class, and the server class starts an HTTP server that never
# returns, so the guard hung the deploy it exists to protect; the second used javap, which is not in
# a JRE image, so every class read as missing and the guard would have refused every deploy.
#
# So: run it, but under a timeout and under a name, and read the error rather than the exit code. A
# class that is absent says so in the first line. A class that loads and then blocks is a pass, and
# the leash plus the name is what stops that leaving a container behind.
for cls in $(grep -ho "tech\.mikhailov\.bjv\.[a-z]*\.[A-Z][A-Za-z]*" "$HERE/run.sh" "$HERE/deploy.sh" | sort -u); do
  run "docker rm -f bjv-probe >/dev/null 2>&1 || true"
  if run "timeout 25 docker run --rm --name bjv-probe --entrypoint java bjv -cp /bump-agent.jar $cls 2>&1 | grep -q 'ClassNotFoundException\|Could not find or load main class'"; then
    run "docker rm -f bjv-probe >/dev/null 2>&1 || true"
    echo "deploy.sh: $cls is launched by a script here but is not in the image" >&2
    exit 1
  fi
  run "docker rm -f bjv-probe >/dev/null 2>&1 || true"
done

# AND A RUNNING LAUNCHER IS NOW STALE, which no image check can fix. bash parses a function when it
# reads it, so a run.sh already executing keeps the class names and paths it started with. Saying so
# is the difference between a restart somebody schedules and an outage somebody discovers.
if pgrep -f "bash ./run.s[h]" >/dev/null 2>&1; then
  echo "deploy.sh: a run.sh launcher may be running with the PREVIOUS script in memory." >&2
  echo "           Class and path changes reach it only on restart; lanes it starts until then" >&2
  echo "           use the old names. Restart it while the in-flight lanes are cheap to lose." >&2
fi

# ONE LONG-LIVED CONTAINER. The dashboard serves the page and runs the supervisor on a daemon
# thread. They were two because the supervisor needed the docker socket to stop a lane; a lane now
# stops itself when it sees its own postponement, so nothing here needs the daemon and a public
# page never shares a container with it.
#
# AS THE USER THE SWEEP RUNS AS, NOT ROOT. The page can now write into the run root — the lane
# count and an uploaded registry — and a container writing as root leaves those files unwritable by
# run.sh. It cost a relaunch: the sort at the top of the sweep failed with "Permission denied" on
# the manifest the dashboard had just renamed into place, and the run carried on reading a file it
# could no longer replace.
#
# THE RUN ROOT IS MOUNTED, NOT JUST THE RESULTS. `max_lanes` sits beside the results directory and
# is re-read by run.sh at the top of every round, which is what makes the lane count adjustable
# while a sweep is going. Mounting one level down meant the settings page could offer a save button
# for a file the container could not see, which is worse than offering nothing.
#
# THE TOKEN IS READ BEFORE THE CONTAINER IS REMOVED. The only copy lives in that container's
# environment, and reading it afterwards recreates a public page with no authentication.
run '
  set -e
  TOKEN=$(docker inspect bjv-dashboard --format "{{range .Config.Env}}{{println .}}{{end}}" 2>/dev/null |
          sed -n "s/^BJV_DASH_TOKEN=//p" | head -1)
  # AND FROM .env WHEN THE CONTAINER IS ALREADY GONE. The comment above was right that the only
  # copy lived in the container, and that is exactly why this deploy could not run: the container
  # was removed while stopping a sweep and the token went with it, unrecoverably. A second copy in
  # .env costs nothing, is already the file every other secret here lives in, and turns a lost
  # token from an outage into a fallback.
  if [ -z "$TOKEN" ] && [ -f "$(dirname "$0")/.env" ]; then
    TOKEN=$(sed -n "s/^BJV_DASH_TOKEN=//p" "$(dirname "$0")/.env" | tr -d "\"" | head -1)
  fi
  if [ -z "$TOKEN" ]; then echo "refusing to recreate the dashboard without its token" >&2; exit 1; fi
  docker rm -f bjv-dashboard bjv-supervisor >/dev/null 2>&1 || true
  ENVFILE=/home/vmihaylov/bump-java-version/agent/.env
  [ -f "$ENVFILE" ] || ENVFILE=/dev/null
  docker run -d --name bjv-dashboard --network proxy-net --restart unless-stopped \
    -e BJV_DASH_TOKEN="$TOKEN" \
    --env-file /home/vmihaylov/bump-java-version/.env \
    --env-file "$ENVFILE" \
    -e OC_KEY="$(sed -n "s/^PROPOSER_API_KEY=//p" /home/vmihaylov/bump-java-version/.env | tr -d "\"" )" \
    -e BJV_SUPERVISOR_MINUTES="${BJV_SUPERVISOR_MINUTES:-20}" \
    --user "$(id -u):$(id -g)" \
    -v '"$(dirname "$RESULTS")"':/runroot \
    bjv tech.mikhailov.bjv.web.Serve /runroot/results 8086 >/dev/null
  echo "dashboard and supervisor recreated as one container"
'

run "docker image inspect bjv --format 'bjv {{.Id}}'"
