---
title: TURN fallback server
description: Configure and host the Nostr4j WebSocket TURN fallback.
---

# TURN fallback server

Some networks prevent a direct p2p connection, and a room may have no usable route through other peers. A Nostr4j TURN server gives the room another path: both peers connect to it over WebSocket, and it forwards their packets. The room can move back to a direct or routed path when one becomes available.

The `nostr4j-turn-server` module accepts authenticated WebSocket connections at `/turn` and forwards encrypted packets. The operator can observe connections, timing and traffic volume, but cannot read application payloads. You can run your own service or use a compatible endpoint whose operator permits your traffic.

This fallback uses a Nostr4j-specific protocol over a `wss://` URL. A conventional ICE TURN server such as coturn cannot serve it.

## Connect the room to the service

Give `NostrRTCLocalPeer` the public endpoint URL and pass a `NostrTURNPool` to `NostrRTCRoom`, as shown in the [room configuration example](p2p-overview.md#turn-fallback-and-path-changes). That URL is advertised through signaling so the other participants can find it. Peers must be able to reach the advertised endpoint for that path to work.

Keep using the room's existing listeners and send methods. The room guide also shows how to force TURN during development and observe path changes.

<a id="build-and-start-the-server"></a>

## Run the published container

The ready-to-run image is available on [GitHub Packages](https://github.com/NostrGameEngine/nostr4j/pkgs/container/nostr4j-turn-server). Pull it and start the server:

```bash
docker pull ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
docker run --rm -p 127.0.0.1:8081:8081 ghcr.io/nostrgameengine/nostr4j-turn-server:snapshot
```

The `snapshot` tag follows development builds. For a fixed release, choose a versioned tag from the package page. The command above publishes port 8081 on the host's loopback address.

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
