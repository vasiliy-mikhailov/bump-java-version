# ONE IMAGE, THREE ENTRY POINTS. The agent, the dashboard and the supervisor are the same jar with
# different main classes, and three Dockerfiles copying the same artifact meant three builds, three
# pushes, and three chances for one of them to be a version behind. The class to run is the command.
#
# The jar is built elsewhere and copied in. It cannot be built here: it needs
# com.deepagents:langchain4j-deepagents in a local repository, which no build host has. See
# deploy.sh, which does the three steps in the one order that works.
#
# THIS TRADES AWAY ONE THING AND IT IS WORTH SAYING. The dashboard used to run from an image with no
# docker client in it at all, so an image that could not reach the daemon could not be talked into
# starting a build. Now it carries one, and the only thing between it and the daemon is that its
# container is run without the socket. The guarantee moved from the image to the run command, which
# is weaker: it is now a thing to get right every time rather than a thing that cannot go wrong.
FROM eclipse-temurin:17-jre-alpine
COPY --from=docker:29-cli /usr/local/bin/docker /usr/local/bin/docker

RUN apk upgrade --no-cache \
 && apk add --no-cache git bash openssh-client ca-certificates
# WHICH COMMIT THIS IMAGE IS, stamped here because nothing downstream can work it out: the lanes
# run the tag bjv and every deploy moves it. The value carries a -dirty suffix when the tree it was
# built from had uncommitted changes, which matters because deploy.sh packages the WORKING TREE and
# not HEAD, so an image can legitimately contain code that is in no commit at all.
ARG BJV_COMMIT=""
RUN printf '%s' "$BJV_COMMIT" > /bjv.commit
COPY target/bump-agent-0.1.0-SNAPSHOT.jar /bump-agent.jar
COPY run.sh /opt/bjv/run.sh
RUN chmod +x /opt/bjv/run.sh
EXPOSE 8086
ENTRYPOINT ["java", "-cp", "/bump-agent.jar"]
CMD ["tech.mikhailov.bjv.agent.Bump"]
