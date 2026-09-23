---
title: TURN server
description: Build, configure and safely deploy the Nostr4J WebSocket TURN fallback server.
---

# TURN server

`nostr4j-turn-server` is the reference WebSocket fallback for RTC peers that cannot establish a direct data channel. It is not a conventional UDP TURN server: clients speak the signed Nostr4J TURN protocol at `/turn`, and the server routes opaque binary payloads between authenticated virtual sockets.

## Build and run

The repository builds a GraalVM native executable and packages it in a non-root distroless image:

```bash
./gradlew :nostr4j-turn-server:test \
  :nostr4j-turn-server:buildNativeExecutable

docker build -t nostr4j-turn-server nostr4j-turn-server
docker run --rm -p 127.0.0.1:8081:8081 nostr4j-turn-server
```

Terminate TLS at a reverse proxy and forward `wss://turn.example.com/turn` to `http://127.0.0.1:8081/turn`. Do not expose the clear-text listener directly to the Internet.

## Configuration

System properties take precedence over environment variables.

| Environment variable | Default | Meaning |
|---|---:|---|
| `NOSTR_TURN_REF_HOST` | `127.0.0.1` (`0.0.0.0` in the image) | Listen address |
| `NOSTR_TURN_REF_PORT` | `8081` | Listen port |
| `NOSTR_TURN_REF_DIFFICULTY` | `13` | Required connect proof-of-work difficulty |
| `NOSTR_TURN_REF_CHALLENGE_TTL_SECONDS` | `30` | Time allowed to authenticate a new socket |
| `NOSTR_TURN_REF_PRIVKEY_HEX` | generated | Stable server identity: hex, `nsec` or `ncryptsec` |
| `NOSTR_TURN_REF_NCRYPTSEC_PASSPHRASE` | none | Passphrase for an encrypted server key |

For production, supply a stable `ncryptsec` through the platform's secret manager. Environment variables are convenient but may be visible to privileged host tooling; a protected process configuration is preferable.

## Built-in limits

The server rejects invalid signatures, wrong event kinds, bad challenge proofs and socket-owner mismatches. Defaults also cap binary WebSocket messages at 1 MiB, virtual sockets at 1,024, queued frames at 2,048, total queued bytes at 64 MiB and queue lifetime at 30 seconds. Unauthenticated clients expire after the configured challenge TTL; idle WebSockets expire after 60 seconds.

Keep those bounds when extending the server. Add external connection/rate limits at the reverse proxy and monitor rejections, queue pressure, memory and open connections.

## Container hardening

The supplied runtime image uses an unprivileged user and contains only the native executable. A production invocation should also use a read-only root filesystem, drop Linux capabilities, set memory/CPU limits and permit writes only to an explicit temporary filesystem if future versions require one.

## Client configuration

Pass the public `wss://…/turn` URI to `NostrRTCLocalPeer` and `NostrRTCRoom`. Auto mode keeps direct WebRTC preferred and uses TURN only when needed; `room.setForceTURN(true)` is useful for tests, not a default deployment policy.
