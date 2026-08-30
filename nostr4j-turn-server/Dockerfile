FROM alpine:3.24

RUN apk upgrade --no-cache && \
    apk add --no-cache libstdc++ gcompat && \
    addgroup -S -g 10001 nostr4j && \
    adduser -S -D -H -u 10001 -G nostr4j nostr4j

WORKDIR /app

COPY build/native/nativeCompile/nostr4j-turn-server /usr/local/bin/nostr4j-turn-server
RUN chmod +x /usr/local/bin/nostr4j-turn-server

ENV NOSTR_TURN_REF_HOST=0.0.0.0

EXPOSE 8081

USER nostr4j

ENTRYPOINT ["/usr/local/bin/nostr4j-turn-server"]
