# Nostr4j demo backend (DemoServer), operator image.
#
# The default final stage builds the combined site + backend image.
# Select the backend-only runtime target for split hosting:
#   docker build --target backend-only -f site-demos/src/backend/docker/Dockerfile -t nostr4j-demo-api .
#
# Build stages:
#   1. site-builder: builds the static site with MkDocs (the mkdocs-material
#      image already contains every plugin the site uses).
#   2. backend-builder: clones the library (master), overlays site-demos
#      from the build context and compiles the demo-backend fat jar with
#      Gradle (needs network for Maven Central + Sonatype snapshots).
#   3. backend-only: Java API and optional cloudflared only; no MkDocs files.
#   4. runtime: eclipse-temurin:21-jre with the jar and the built site.
#
# DemoServer serves the static site and the /api/* demo endpoints on a single
# port (same origin by default, so the browser demos can reach the backend).
# It makes outbound relay/TURN connections; when configured, cloudflared also
# makes outbound Tunnel connections. Direct WebRTC also needs UDP paths to
# the peer's ICE candidates; the tunnel carries HTTP/WebSocket traffic only.
#
# Build from the gh-pages branch root:
#   docker build -f site-demos/src/backend/docker/Dockerfile -t nostr4j-demo-backend .

# --- static site -----------------------------------------------------------
FROM docker.io/squidfunk/mkdocs-material:latest AS site-builder
WORKDIR /docs
ARG NOSTR4J_DEMO_API_BASE_URL=
ENV NOSTR4J_DEMO_API_BASE_URL=${NOSTR4J_DEMO_API_BASE_URL}
COPY mkdocs.yml ./
COPY docs ./docs
COPY theme ./theme
COPY scripts ./scripts
COPY site-demos ./site-demos
RUN mkdocs build -d /site-out

# --- Cloudflare Tunnel client ---------------------------------------------
FROM docker.io/cloudflare/cloudflared:latest AS cloudflared

# --- backend jar -----------------------------------------------------------
FROM docker.io/library/eclipse-temurin:25-jdk AS backend-builder
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
# Library sources live on master; site-demos lives on the gh-pages branch.
RUN git clone --depth 1 --branch master https://github.com/NostrGameEngine/nostr4j.git master \
    && git clone --depth 1 --branch master https://github.com/NostrGameEngine/nge-platforms.git platforms
WORKDIR /src/platforms
RUN ./gradlew :nge-platform-common:publishToMavenLocal :nge-platform-jvm:publishToMavenLocal :nge-platform-teavm:publishToMavenLocal --no-daemon
WORKDIR /src
COPY site-demos master/site-demos
RUN grep -q 'site-demos' master/settings.gradle \
    || printf '\ninclude("site-demos")\n' >> master/settings.gradle
WORKDIR /src/master
RUN ./gradlew :site-demos:jvmSmokeTest :site-demos:documentationSmokeTest :site-demos:backendJar --no-daemon -x test

# --- API-only runtime ------------------------------------------------------
# Select with `docker build --target backend-only ...` when MkDocs is hosted
# separately. This image serves /api/* only and does not contain the site.
FROM docker.io/library/eclipse-temurin:21-jre AS backend-only
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tini ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY --from=cloudflared /usr/local/bin/cloudflared /usr/local/bin/cloudflared
COPY --from=backend-builder --chown=10001:10001 /src/master/site-demos/build/libs/demo-backend*.jar /app/demo-backend.jar
COPY site-demos/src/backend/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh
ENV DEMO_PORT=8080 \
    DEMO_API_ONLY=true \
    TURN_URI=wss://turn.ngengine.org/turn
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["tini", "-g", "--", "/app/entrypoint.sh"]

# --- runtime ---------------------------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tini ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY --from=cloudflared /usr/local/bin/cloudflared /usr/local/bin/cloudflared
COPY --from=backend-builder --chown=10001:10001 /src/master/site-demos/build/libs/demo-backend*.jar /app/demo-backend.jar
COPY --from=site-builder --chown=10001:10001 /site-out /site
COPY site-demos/src/backend/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh

# Defaults; override at run time with -e. DEMO_HOSTS must list every public
# hostname the site is reached at, otherwise the server answers 403.
ENV DEMO_PORT=8000 \
    DEMO_ROOT=/site \
    TURN_URI=wss://turn.ngengine.org/turn
EXPOSE 8000

USER 10001:10001
ENTRYPOINT ["tini", "-g", "--", "/app/entrypoint.sh"]
