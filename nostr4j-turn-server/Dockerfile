FROM alpine:3.20

RUN apk add --no-cache libstdc++ gcompat

WORKDIR /app

COPY build/native/nativeCompile/nostr4j-turn-server /usr/local/bin/nostr4j-turn-server
RUN chmod +x /usr/local/bin/nostr4j-turn-server

ENV NOSTR_TURN_REF_HOST=0.0.0.0

EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/nostr4j-turn-server"]
