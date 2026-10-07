---
title: Onion routing
---

# Onion routing

<a class="doc-run-button" href="../demos.html#game">Run routed game</a>

Imagine Alice and Carol cannot connect directly, but both have a connection to Bob. In Nostr4J, Bob can carry a message between them: Alice still sends to Carol, and Carol receives the message through her room's normal listener.

This avoids a full mesh: no participant needs a direct connection to everyone else. It is part of `NostrRTCRoom`: the same call works whether the destination is a neighbor or several hops away:

```java
room.send(peer, ByteBuffer.wrap(message));
```

## How the room finds a route

Peers publish signed topology snapshots describing their connections. Only links acknowledged by both ends are kept, and that view is refreshed as participants join, leave or change connections.

A working direct WebRTC link takes priority. Otherwise the lower-cost option wins between a routed path through other peers and an available direct TURN path; address the destination the same way in your application.

New connections take time to appear in the topology. A peer that just joined may be known before a route to it is usable, and a cut-off part of the room stays unreachable until a path is restored. Handle send failures, and resynchronize your application when connectivity returns. A [TURN fallback](turn-server.md) can provide another path when peers cannot establish the needed WebRTC links.

## Choosing how many direct peers to maintain

By default, each peer keeps up to 16 direct neighbors. To change it, adjust the settings you use to create the local peer and room:

```java
RTCSettings settings = RTCSettings.getDefault("my-app", "chat-v1")
    .withMaxDirectPeers(8);
```

The supported range is 2 to 64. Use the same settings on all participants in your session. A lower value means fewer direct connections and more forwarding; a higher value gives the room more direct links to work with. Additional participants can still be reached through those neighbors, so this setting does not limit room membership.

## What happens at each hop

Route setup wraps the instructions in encryption layers. Each forwarding peer removes its own layer to learn the next hop, and keeps the state needed to forward packets along that route. Once the route is established, application packets travel through that circuit with their payload encrypted for the destination.

In the Alice-Bob-Carol example, Bob can forward Alice's packet without reading the message she sent to Carol. Bob still sees his adjacent connections and the timing and volume of traffic passing through them. Onion routing protects the payload from forwarding peers; it does not promise anonymity. The `turn` flag in a message callback does not reveal the full route either.

## Broadcasting to the room

Use `room.broadcast(buffer)` when an update is intended for the group. When the topology's links are confirmed by both ends, the message is distributed along a tree - your application does not send a separate routed message to each recipient.

Each routed broadcast carries its origin's session signature. Receivers verify it against authenticated topology before delivering or forwarding the frame, so a forwarding peer cannot change its payload or impersonate its origin. Broadcast content is intended for every authorized room member; it does not have the destination-only confidentiality of routed unicast.

In a small room where that topology is not yet available, reach individual peers with ready channels instead. This fallback skips unavailable channels and waits for the send attempts to settle. Treat broadcast completion accordingly: if every participant must confirm an action, collect replies in your application protocol.

The [NIP-DC specification](https://github.com/NostrGameEngine/nostr4j/blob/master/NIP-DC.md) covers topology validation, route setup, encrypted packets and broadcast delivery in detail.

Use compatible library versions across a room. Older peers without signed broadcast support cannot exchange the current broadcast format.
