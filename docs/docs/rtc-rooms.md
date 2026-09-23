---
title: RTC rooms
---

# RTC rooms

NostrRTC (draft NIP-DC, protocol version `dc4`) turns a Nostr room into a realtime peer-to-peer mesh: WebRTC data channels carry the payloads, Nostr is only used for signaling (kind `25050` events). The application sees one logical connection per peer; underneath, the room prefers direct WebRTC, degrades to TURN when direct fails, switches back when it recovers, and pauses delivery when nothing is usable.

## The model

## Protocol and interoperability

The [NIP-DC specification](https://github.com/NostrGameEngine/nostr4j/blob/master/NIP-DC.md) defines signaling, transport negotiation, packet framing and routed rooms independently of Java. An implementation in another language must match the protocol version and wire format, not Java classes.

A logical channel survives changes between direct WebRTC and Nostr4J WebSocket TURN, including retry through a different TURN endpoint. Fragmentation and packet identifiers support reassembly and bounded duplicate suppression across retries. Signaling refreshes, transport recovery and topology repair provide self-healing behavior; they are not a guarantee of connectivity during a partition. Deduplication windows and queue limits are finite, so applications still need their own transaction IDs for exactly-once business operations.

Nostr4J TURN is a custom WebSocket protocol, not an RFC 8656/coturn ICE server. Use a compatible `wss://` endpoint; a `turn:` URL cannot be substituted. See [TURN setup](turn-server.md) and the [onion-routing model](onion-routing.md).

### Room, peer and channel

- **Room** = a Nostr keypair shared by everyone allowed in. Possessing the room private key *is* the authorization.
- **Peer** = your own keypair (ephemeral is best) plus a session id.
- **Channel** = a named logical path between two peers with its own ordering and retransmission state. A `default` channel (ordered, reliable) is opened automatically.

## Joining a room

Your local peer holds your signer, the STUN servers for ICE, an application id and protocol id that delimit the room, the shared room keypair, and an optional TURN server:

```java
NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair()); // your identity

NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
    signer,
    RTCSettings.PUBLIC_STUN_SERVERS,  // to discover your public IP
    "my-app",                         // application id
    "my-protocol-v1",                 // protocol id
    roomKeyPair,                      // the shared room keypair
    null                              // TURN server URL, null = direct only
);

NostrTURNPool turnPool = new NostrTURNPool();
NostrRTCRoom room = new NostrRTCRoom(
    RTCSettings.DEFAULT,
    localPeer,
    roomKeyPair,
    signalingPool,                    // a NostrPool for the kind-25050 signaling
    null,                             // TURN server URL (null here: direct only)
    turnPool
);
room.start().get();
```

`RTCSettings.DEFAULT` carries sane timeouts; `withMaxDirectPeers(n)` caps how many direct connections a peer keeps (default 16, minimum 2) - extra peers are reached through [onion routing](onion-routing.md) instead. The signaling pool is just a normal `NostrPool` connected to relays; the room publishes and reads its signaling events through it.

## Sending and receiving

When a peer's socket becomes available, send on the default channel or open a named one:

```java
room.addPeerSocketAvailableListener((peer, socket) ->
    room.createChannel(peer, "chat"));

room.send("chat", peer, ByteBuffer.wrap("hello".getBytes()));
room.send(peer, ByteBuffer.wrap("hi on default".getBytes()));
room.broadcast(ByteBuffer.wrap("hello everyone".getBytes()));
```

`send` has three forms: `(peer, buffer)` on the default channel, `(channelName, peer, buffer)`, and `(channel, buffer)`. `broadcast` reaches every connected peer and completes even if some sends fail. For full control over reliability, `createChannel` takes `ordered`, `reliable`, `maxRetransmits` and `maxPacketLifeTime`; a channel also exposes `write(buffer)` directly.

Incoming messages arrive at room level:

```java
room.addMessageListener((peer, socket, channel, bbf, turn) -> {
    byte[] b = new byte[bbf.remaining()];
    bbf.get(b);
    System.out.println("from " + peer + " via " + (turn ? "TURN" : "direct"));
});
```

The `turn` flag tells you which transport carried *that* message. There is also a channel-level listener (`NostrRTCChannelListener.onRTCSocketMessage`) with the same flag.

## Which transport am I really on?

This is the question the demo page answers live. Three facts:

1. `NostrRTCSocket.TransportPath` has three values: `NONE`, `RTC`, `TURN`. But `getActiveTransportPath()` is **not public** - you can't poll it.
2. The per-message `turn` flag tells you the transport of each message.
3. Transport switches are reported by a socket listener:

```java
socket.addListener(new NostrRTCSocketListener() {
    @Override
    public void onRTCSocketTransportSwitch(NostrRTCSocket socket,
            TransportPath from, TransportPath to, String reason) {
        System.out.println("transport: " + from + " -> " + to + " (" + reason + ")");
    }
});
```

There is also `onRTCSocketTransportDegraded` for when the active transport gets worse without fully switching. Never infer the transport from your configuration - always use the callback or the flag.

Two knobs control TURN behavior: `room.setForceTURN(true)` forces everything through TURN (and `isForceTURN()` reads it back), while passing `turnServerUrl = null` **and** `turnPool = null` to the room constructor means direct-only with no TURN fallback at all.

## Lifecycle

```java
room.addPeerDiscoveryListener((peer, announce, state) -> { /* a peer showed up */ });
room.addDisconnectionListener((peer, socket) -> { /* a peer left */ });

room.getSockets();            // every live socket
room.getSocket(peer);         // one peer's socket
room.getLocalPeerInfo();      // yourself

room.disconnect(peer);        // drop a peer's socket
room.disconnect(pubkey);      // disconnect by public key
room.ban(pubkey);             // ban / unban
room.unban(pubkey);
```

`discover()` triggers a fresh round of peer discovery; the room also rediscovers on its own loop.

The full wire spec is in [NIP-DC.md](https://github.com/NostrGameEngine/nostr4j/blob/master/NIP-DC.md) at the repo root.

## Where next

- [Onion routing](onion-routing.md) - multi-hop messaging across the mesh
- [NIPs index](nips.md)
