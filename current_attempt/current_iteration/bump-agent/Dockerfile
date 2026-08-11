# The controller: a JDK to run the chain, and a docker client to reach the sealed build images.
# It never builds anything itself — jvm-run does that in a per-JDK container — so this image carries
# no build tooling and no Maven cache of its own. Keeping the arbiter outside the process that hosts
# the agents is the point of the split, not an accident of packaging.
FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends docker.io git ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY target/bump-agent-0.1.0-SNAPSHOT.jar /bump-agent.jar
ENTRYPOINT ["java", "-jar", "/bump-agent.jar"]
