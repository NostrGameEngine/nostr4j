# RTC ping/pong receiver (demo 3) — operator image.
#
# Two-stage build:
#   1. builder: compiles the fat receiver jar with Gradle (needs network for
#      Maven Central + Sonatype snapshots).
#   2. runtime: eclipse-temurin:21-jre with just the jar.
#
# The receiver only makes OUTBOUND connections (Nostr relay WebSockets and the
# TURN server over wss://); it listens on no ports, so nothing needs exposing.

FROM docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY . .
RUN ./gradlew :site-demos:receiverJar --no-daemon -x test

FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY --from=builder --chown=10001:10001 /src/site-demos/build/libs/ping-receiver*.jar /app/ping-receiver.jar

# Defaults; override at run time with -e.
ENV ROOM_KEY_SEED=nostr4j-demo-ping-v1 \
    RELAYS=wss://relay.ngengine.org \
    TURN_URI=wss://turn.ngengine.org/turn

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/ping-receiver.jar"]
