FROM gcr.io/distroless/base-debian13:nonroot

WORKDIR /app

COPY --chown=10001:10001 build/native/nativeCompile/nostr4j-turn-server /usr/local/bin/nostr4j-turn-server

ENV NOSTR_TURN_REF_HOST=0.0.0.0

EXPOSE 8081

USER 10001:10001

ENTRYPOINT ["/usr/local/bin/nostr4j-turn-server"]
