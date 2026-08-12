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
mvn -B -o package
rsync -a --delete src/ "$H:$R/src/"
rsync -a target/bump-agent-0.1.0-SNAPSHOT.jar "$H:$R/target/"
ssh "$H" "cd $R && docker build -q -t bjv-agent ."
ssh "$H" "docker image inspect bjv-agent --format 'deployed {{.Id}}'"
