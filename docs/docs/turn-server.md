---
title: Host a fallback server
description: Run a Nostr4J WebSocket TURN service for peers that need a fallback connection.
---

# Host a fallback server

Use a Nostr4j TURN service when peers cannot connect directly or reach each other through the room. It forwards encrypted data over WebSockets.

![Peers use Nostr for signaling, WebRTC for direct data, and Nostr4j TURN as an optional fallback.](../assets/diagrams/rtc-paths.svg)

!!! note "Compatible service"
    Use the `nostr4j-turn-server` module or a compatible hosted endpoint. This fallback uses Nostr4j's WebSocket protocol; conventional ICE TURN servers such as coturn cannot serve it.

!!! tip "End-to-end encrypted"
    Payloads are encrypted between peers. The TURN server operator cannot read their contents.

## Connect the room to the service

For rooms, supply the endpoint URL and TURN pool as shown in [Add a TURN fallback](rtc-rooms.md#add-an-optional-turn-fallback). For streams, pass the URL to the [connection constructor](simple-rtc-connection.md#choose-relays-or-add-a-fallback). Use an address reachable by both participants.

## Run the reference implementation server

The ready-to-run image is available on [GitHub Packages](https://github.com/NostrGameEngine/nostr4j/pkgs/container/nostr4j-turn-server):

```bash
docker pull ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
docker run --rm -p 127.0.0.1:8081:8081 ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
```

!!! tip "Choose a version"
    The `snapshot` tag follows development builds. Use a versioned tag for a fixed release.

You can also build the server from the [GitHub source](https://github.com/NostrGameEngine/nostr4j/tree/master/nostr4j-turn-server). With GraalVM 25 and `native-image` installed, run these commands from the repository root:

```bash
./gradlew :nostr4j-turn-server:test :nostr4j-turn-server:buildNativeExecutable
docker build -t nostr4j-turn-server nostr4j-turn-server
docker run --rm -p 127.0.0.1:8081:8081 nostr4j-turn-server
```

Build the executable before the image. The container runs as an unprivileged user; the command exposes port 8081 on the host's loopback address.

Put a TLS reverse proxy in front of the server to expose `/turn` over `wss://`, the secure WebSocket endpoint used by clients.

## Configure the service

Pass environment variables when starting the container to change its listen address, authentication settings or identity. When running the server directly, system properties take precedence over the corresponding environment variables.

| Environment variable | Default | Purpose |
|---|---|---|
| `NOSTR_TURN_REF_HOST` | `127.0.0.1`; `0.0.0.0` in the image | Address to listen on |
| `NOSTR_TURN_REF_PORT` | `8081` | Port to listen on |
| `NOSTR_TURN_REF_DIFFICULTY` | `13` | Required proof-of-work difficulty when connecting |
| `NOSTR_TURN_REF_CHALLENGE_TTL_SECONDS` | `30` | Time allowed to authenticate a new socket |
| `NOSTR_TURN_REF_PRIVKEY_HEX` | Generated at startup | Server identity, supplied as hex, `nsec` or `ncryptsec` |
| `NOSTR_TURN_REF_NCRYPTSEC_PASSPHRASE` | Unset | Passphrase for an `ncryptsec` server key |

!!! tip "Keep a stable server identity"
    Supply a key for deployed services so the identity survives restarts. Store it and any passphrase in your deployment's secret storage.
