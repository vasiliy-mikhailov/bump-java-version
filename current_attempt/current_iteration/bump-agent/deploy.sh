#!/usr/bin/env bash
# Build here, ship the jar, then build the image there. IN THAT ORDER, AND ALL THREE.
#
# The Dockerfile copies target/bump-agent-0.1.0-SNAPSHOT.jar rather than building it, and the jar
# can only be built on a machine that has com.deepagents:langchain4j-deepagents installed locally,
# which the host does not. So syncing src/ and running `docker build` on the host does exactly
# nothing: the COPY layer hits cache and the image ships the previous jar. It reports success. A
# live sweep ran an hour of already-committed-and-pushed code that way, and the trace is what
# caught it, not the deploy.
set -euo pipefail
cd "$(dirname "$0")"
H=${BJV_HOST:-mh}
R=${BJV_REMOTE:-/home/vmihaylov/bump-java-version/current_attempt/current_iteration/bump-agent}

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
RESULTS=${BJV_RESULTS:-/home/vmihaylov/bump-java-version/current_attempt/current_iteration/runs_agent/results}
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
rm -rf src/main/resources/ui
cp -r ui/apps/web/out src/main/resources/ui
mvn -B -o package
if [ -z "$LOCAL" ]; then
  rsync -a --delete src/ "$H:$R/src/"
  rsync -a target/bump-agent-0.1.0-SNAPSHOT.jar "$H:$R/target/"
fi
# THE DOCKERFILES TOO. They live here and are built there, so a new one is a build failure and an
# edited one is silently the old one -- the same shape as this script not shipping itself, which
# also had to be found the hard way.
if [ -z "$LOCAL" ]; then
  rsync -a Dockerfile run.sh deploy.sh "$H:$R/"
fi
# ONE IMAGE. The agent, the dashboard and the supervisor are the same jar with different main
# classes; three Dockerfiles copying the same artifact meant three builds and three chances for one
# of them to be a version behind.
run "cd $R && docker build -q -t bjv ."

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
  if [ -z "$TOKEN" ]; then echo "refusing to recreate the dashboard without its token" >&2; exit 1; fi
  docker rm -f bjv-dashboard bjv-supervisor >/dev/null 2>&1 || true
  ENVFILE=/home/vmihaylov/bump-java-version/current_attempt/current_iteration/bump-agent/.env
  [ -f "$ENVFILE" ] || ENVFILE=/dev/null
  docker run -d --name bjv-dashboard --network proxy-net --restart unless-stopped \
    -e BJV_DASH_TOKEN="$TOKEN" \
    --env-file /home/vmihaylov/bump-java-version/.env \
    --env-file "$ENVFILE" \
    -e OC_KEY="$(sed -n "s/^PROPOSER_API_KEY=//p" /home/vmihaylov/bump-java-version/.env | tr -d "\"" )" \
    -e BJV_SUPERVISOR_MINUTES="${BJV_SUPERVISOR_MINUTES:-20}" \
    --user "$(id -u):$(id -g)" \
    -v '"$(dirname "$RESULTS")"':/runroot \
    bjv tech.mikhailov.bjv.agent.Dashboard /runroot/results 8086 >/dev/null
  echo "dashboard and supervisor recreated as one container"
'

run "docker image inspect bjv --format 'bjv {{.Id}}'"
