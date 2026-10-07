---
title: How RTC connections work
---

# How RTC connections work

This page explains how application instances find each other through Nostr and exchange data over WebRTC. A game, for example, can use a Nostr relay to bring players into the same session, then send game messages between their devices. The relay carries discovery and signaling; the connections in the room carry application data.

The central object is `NostrRTCRoom`. Use it as the place to send messages, receive them, and follow peers as they connect or leave. A room works with two participants or a larger group, and can carry several named channels for different kinds of traffic.

Use it for multiplayer state updates, chat messages or file transfers. The library carries bytes; your application defines the message format. To connect existing Java I/O code between two peers, start with [Simple P2P streams](simple-rtc-connection.md).

## Joining the same room

Each participant has its own Nostr identity, represented by a signer. The participants also share a room key pair, which identifies the room and lets them prove they belong to it. For a private session, generate this key pair once and distribute it through your application's invitation flow. Each participant loads the same room key; their personal keys stay private.

An application ID identifies your app and a protocol ID identifies the message format it speaks. Peers need matching IDs and the same room key to join the same group. See [room access](security.md#rtc-room-access) for an example of creating and sharing that key.

These IDs belong in `RTCSettings`, along with signaling relays and connection policy. Use the same settings values on all participants. The room key and each participant's signer are supplied separately. A local peer also has a session ID to identify that particular instance; the convenience constructor generates it for you.

## From discovery to a working connection

When you start a room, your peer is announced through the configured Nostr relays, and your listeners pick up other participants as they appear. Peers then exchange the signaling needed to establish WebRTC connections, including the information used to find a path through their networks.

Discovery can use the newest authenticated presence already stored by a relay, so you need not wait for the next announcement. Expired presence and events dated more than 30 seconds into the future are rejected. Stored offers, answers and routes are not replayed into a new connection attempt.

Discovery and connection happen over time. Install your listeners before starting the room, so they catch the first connection; they tell your application when a peer has a socket available or sends a message.

Your application then works with peers and byte buffers:

```java
room.send(peer, ByteBuffer.wrap(message));
room.broadcast(ByteBuffer.wrap(announcement));
```

Here, `message` and `announcement` are byte arrays in whatever format your application uses: a JSON document, a game update or a serialized Nostr event. On the receiving side, you get the sender, channel and payload, so you can dispatch each message to the right part of your app.

The [room guide](rtc-rooms.md) shows how to create a room and wire up those listeners.

For JVM applications built around `InputStream` and `OutputStream`, the [peer connection adapter](rtc-streams.md) provides a blocking byte stream over the same transports.

## Reaching the rest of the group

A room does not need a direct connection between every pair of participants. To reach the rest of the group, messages are forwarded through each peer's direct neighbors - see [onion routing](onion-routing.md). Your application still addresses the destination peer and uses the same `send` call.

A direct WebRTC connection is used whenever it works; the alternatives are a routed path through other peers or a configured [TURN fallback service](turn-server.md). Paths update as connectivity changes, while your application keeps using the same channels and listeners.

A send can fail while a peer is offline or the network is changing, so handle its returned task and decide how your application should recover. For a shared document that might mean fetching a fresh revision; for a game it might mean sending the latest state when the peer returns.

The [NIP-DC specification](https://github.com/NostrGameEngine/nostr4j/blob/master/NIP-DC.md) describes the signaling, routing and packet formats used by these connections.
