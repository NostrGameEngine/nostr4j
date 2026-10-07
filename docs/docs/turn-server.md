---
title: Host a fallback server
description: Run a Nostr4J WebSocket TURN service for peers that need a fallback connection.
---

# Host a fallback server

Some networks make direct WebRTC connections difficult, and a room may not have another peer available to forward traffic. To cover those cases, configure a Nostr4J TURN service in the room: it gives those peers another way to communicate while direct connectivity is still being attempted.

To run a compatible service, use the `nostr4j-turn-server` module: it accepts authenticated WebSocket connections at `/turn` and forwards encrypted packets. The operator can observe connections, timing and traffic volume, but application payloads stay encrypted between peers. You can run your own service or use a compatible endpoint whose operator permits your traffic.

The fallback speaks its own protocol over a `wss://` URL, so it needs a Nostr4J-compatible server: conventional ICE TURN endpoints such as coturn cannot serve this protocol.

## Connect the room to the service

Give `NostrRTCLocalPeer` the public endpoint URL and pass a `NostrTURNPool` to `NostrRTCRoom`, as shown in the [client configuration example](rtc-rooms.md#add-an-optional-turn-fallback). That URL is advertised through signaling so the other participants can find it. Peers must be able to reach the advertised endpoint for that path to work.

Keep using your room's existing listeners and send methods; that example also shows how to force the fallback for development-time testing.

Use compatible client and server versions. Delivery receipts authenticate the exact encrypted frame they acknowledge; older servers that send empty receipts cannot complete current client writes, even if the WebSocket connection succeeds.

## Run the published container

The ready-to-run image is available on [GitHub Packages](https://github.com/NostrGameEngine/nostr4j/pkgs/container/nostr4j-turn-server):

```bash
docker pull ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
docker run --rm -p 127.0.0.1:8081:8081 ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
```

The `snapshot` tag follows development builds. For a fixed release, choose a versioned tag from the package page.

## Build and start the server

The server ships as a native binary built with GraalVM 25 `native-image`, packaged in its container. From the library checkout, run:

```bash
./gradlew :nostr4j-turn-server:test :nostr4j-turn-server:buildNativeExecutable
docker build -t nostr4j-turn-server nostr4j-turn-server
docker run --rm -p 127.0.0.1:8081:8081 nostr4j-turn-server
```

Build the executable before the image: the Dockerfile copies `build/native/nativeCompile/nostr4j-turn-server` from the server module. The container runs as an unprivileged user. The command above publishes port 8081 on the host's loopback address.

Put a TLS reverse proxy in front of the server and forward `wss://turn.example.com/turn` to its `/turn` endpoint. Enable WebSocket upgrades in the proxy. If the proxy runs in another container, place both containers on an internal network so it can reach the server there.

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

A generated identity is convenient for a local test. For a deployed service, supply a stable key so its identity survives restarts. Despite the variable's name, `NOSTR_TURN_REF_PRIVKEY_HEX` also accepts `nsec` and encrypted `ncryptsec` values. Keep the key and any passphrase in your deployment's secret storage.

As the service takes on traffic, monitor connection counts, queued data and rejected requests. Frame and queue limits are enforced server-side; size the host's and the reverse proxy's connection and resource limits for the capacity you intend to provide.
