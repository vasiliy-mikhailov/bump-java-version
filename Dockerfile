# Multi-JDK runner image for the OpenRewrite fitness loop.
# One image, four JDKs (8/11/17/21), Maven 3.9.x, Gradle 8.x, git, jq.
# The orchestrator picks the JDK per-repo via SDKMAN.
FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    SDKMAN_DIR=/opt/sdkman \
    PATH=/opt/sdkman/bin:/opt/sdkman/candidates/maven/current/bin:/opt/sdkman/candidates/gradle/current/bin:/opt/sdkman/candidates/java/current/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        jq \
        time \
        unzip \
        zip \
        coreutils \
    && rm -rf /var/lib/apt/lists/*

# SDKMAN gives us per-shell JDK switching with `sdk use java <ver>`.
# Versions pinned to current LTS Temurin builds; bump as needed.
RUN curl -s "https://get.sdkman.io" | bash \
    && bash -lc " \
        source /opt/sdkman/bin/sdkman-init.sh && \
        sdk install java 8.0.422-tem  && \
        sdk install java 11.0.24-tem  && \
        sdk install java 17.0.12-tem  && \
        sdk install java 21.0.4-tem   && \
        sdk install maven 3.9.9         && \
        sdk install gradle 8.10.2       && \
        sdk flush archives && sdk flush temp \
    "

# OpenRewrite is invoked via the Maven/Gradle plugin from a fully-online
# Maven repo, so no further binary install needed. We seed a local
# repo cache to make repeated runs cheap.
ENV MAVEN_OPTS="-Xmx2g -Dorg.slf4j.simpleLogger.defaultLogLevel=warn" \
    GRADLE_OPTS="-Xmx2g -Dorg.gradle.daemon=false"

WORKDIR /work
COPY scripts /opt/scripts
RUN chmod +x /opt/scripts/*.sh

ENTRYPOINT ["/opt/scripts/run_one_repo.sh"]
