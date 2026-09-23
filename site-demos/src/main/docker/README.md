# RTC ping/pong receiver (demo 3)

> This standalone receiver is for manual protocol experiments. The current
> website's one-click ping uses **DemoServer**, which allocates a separate
> room per visitor. Build `:site-demos:backendJar` to run the site's server.
> A shared standalone receiver does not provide visitor isolation.

This legacy standalone peer joins a single configured public room, announces
itself with periodic `HELLO` frames and answers `PING` with `PONG`. It is not
used by the current website. The site's backend creates an isolated receiver
for each visitor instead.

Only **outbound** TCP is needed (Nostr relay WebSockets + the TURN server over
`wss://`). The receiver listens on **no ports**.

## Build

From the repository root (needs network for Gradle dependencies):

```bash
docker build -f site-demos/src/main/docker/Dockerfile -t nostr4j-ping-receiver .
```

Or build just the fat jar and run it directly (Java 21+):

```bash
./gradlew :site-demos:receiverJar --no-daemon
java -jar site-demos/build/libs/ping-receiver-0.3.0-SNAPSHOT.jar
```

## Run

```bash
docker run -d --name ping-receiver --restart unless-stopped \
  --read-only --tmpfs /tmp:rw,nosuid,nodev,size=16m \
  --cap-drop ALL --security-opt no-new-privileges \
  --memory 384m --cpus 1 \
  -e ROOM_KEY_SEED=nostr4j-demo-ping-v1 \
  -e RELAYS=wss://relay.ngengine.org \
  -e TURN_URI=wss://turn.ngengine.org/turn \
  nostr4j-ping-receiver
```

Check the logs — you should see the room id and a `HELLO` broadcast tick:

```bash
docker logs -f ping-receiver
# [ping-receiver] room id   : <64 hex chars>
# [ping-receiver] room started, broadcasting HELLO every 5000 ms
```

## Configuration (environment variables)

| Variable        | Default                          | Meaning                                                        |
|-----------------|----------------------------------|----------------------------------------------------------------|
| `ROOM_KEY_SEED` | `nostr4j-demo-ping-v1`           | Public standalone room seed. Other standalone peers must use the same seed; both sides derive the room key as `SHA-256(seed)`. Never reuse a secret production room key here. |
| `RELAYS`        | `wss://relay.ngengine.org`        | Comma-separated Nostr signaling relays.                        |
| `TURN_URI`      | `wss://turn.ngengine.org/turn`   | TURN server URI for the TURN fallback / forced-TURN demo mode.  |

## How it works

1. The receiver derives the room key pair as `SHA-256(ROOM_KEY_SEED)` (see
   `PingProtocol.deriveRoomKeys`). This is for independently configured
   standalone peers, not the current website's per-visitor rooms.
2. Every 5 s it broadcasts `HELLO` on the `ping-v1` control channel (and also
   greets each newly connected peer directly). A standalone client can learn
   the receiver's peer identity from that frame and use it as the ping target.
3. On `PING:<nanos>` it replies `PONG:<nanos>` on the same channel. The
   browser measures RTT from the echoed timestamp and reports the transport
   nostr4j actually used for that message (per-message `turn` flag).

The receiver accepts only `wss://` relay/TURN URLs, caps configuration sizes,
ignores frames above 128 bytes and rate-limits replies per peer. It logs the
derived room id, never the room seed. The image runs as an unprivileged user;
the hardened `docker run` example also removes Linux capabilities and makes
the root filesystem read-only. `/tmp` must remain executable because
`SaferAlloc` extracts its pinned JNI library there; it is still an isolated,
size-limited `nosuid,nodev` tmpfs.
