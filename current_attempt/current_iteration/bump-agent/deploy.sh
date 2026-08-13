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
RESULTS=${BJV_RESULTS:-/home/vmihaylov/bump-java-version/current_attempt/current_iteration/runs_agent/results}
mvn -B -o package
rsync -a --delete src/ "$H:$R/src/"
rsync -a target/bump-agent-0.1.0-SNAPSHOT.jar "$H:$R/target/"
# THE DOCKERFILES TOO. They live here and are built there, so a new one is a build failure and an
# edited one is silently the old one -- the same shape as this script not shipping itself, which
# also had to be found the hard way.
rsync -a Dockerfile run.sh deploy.sh "$H:$R/"
# ONE IMAGE. The agent, the dashboard and the supervisor are the same jar with different main
# classes; three Dockerfiles copying the same artifact meant three builds and three chances for one
# of them to be a version behind.
ssh "$H" "cd $R && docker build -q -t bjv ."

# A new image does not reach a running container, so both long-lived ones are recreated. THE
# DASHBOARD TOKEN IS READ BEFORE ITS CONTAINER IS REMOVED, because the only copy of it lives in that
# container's environment: reading it afterwards recreates a public page with no authentication.
#
# The dashboard gets no docker socket. It used to run from an image with no docker client at all,
# which was the stronger guarantee; with one image that protection lives here instead, so this line
# is now load-bearing rather than belt-and-braces.
ssh "$H" '
  set -e
  TOKEN=$(docker inspect bjv-dashboard --format "{{range .Config.Env}}{{println .}}{{end}}" 2>/dev/null |
          sed -n "s/^BJV_DASH_TOKEN=//p" | head -1)
  if [ -z "$TOKEN" ]; then echo "refusing to recreate the dashboard without its token" >&2; exit 1; fi
  docker rm -f bjv-dashboard >/dev/null 2>&1 || true
  docker run -d --name bjv-dashboard --network proxy-net --restart unless-stopped \
    -e BJV_DASH_TOKEN="$TOKEN" -v '"$RESULTS"':/results \
    bjv tech.mikhailov.bjv.agent.Dashboard /results 8086 >/dev/null
  echo "dashboard recreated"
'

# The supervisor DOES get the socket: setting a lane aside means stopping the lane.
ssh "$H" '
  docker rm -f bjv-supervisor >/dev/null 2>&1 || true
  docker run -d --name bjv-supervisor --restart unless-stopped \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v '"$RESULTS"':/results \
    --env-file /home/vmihaylov/bump-java-version/.env \
    -e OC_KEY="$(sed -n "s/^PROPOSER_API_KEY=//p" /home/vmihaylov/bump-java-version/.env | tr -d "\"" )" \
    -e BJV_SUPERVISOR_MINUTES="${BJV_SUPERVISOR_MINUTES:-20}" \
    bjv tech.mikhailov.bjv.agent.Supervisor /results >/dev/null && echo "supervisor recreated"
'

ssh "$H" "docker image inspect bjv --format 'bjv {{.Id}}'"
