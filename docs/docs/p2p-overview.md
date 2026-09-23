---
title: P2P connections
---

# P2P connections

<a class="doc-run-button" href="../demos.html#rtc">Run RTC ping demo</a>

Normally, Nostr applications communicate by publishing events to relays. However, some applications need communication channels that offer lower latency, higher bandwidth, or greater privacy than relays can provide.

For these cases, Nostr4j provides a managed peer-to-peer communication framework. Relays are used only for initial discovery and coordination; once peers have discovered each other, Nostr4j establishes peer-to-peer connections for the actual data transfer using a variety of transport and connectivity techniques.

All of this complexity is completely transparent to the application developer. From the application's point of view, Nostr4j exposes a simple API for sending and receiving bytes between peers, without relying on a central server for direct connections. The speeds and bandwidth depend on the path connecting the peers.

For a two-peer connection through Java `InputStream` and `OutputStream`, see [Simple P2P streams](simple-rtc-connection.md).

Typical use cases include:

- **Video games**, where low latency and frequent state updates are important.
- **Video conferencing**, where audio and video streams require both low latency and high bandwidth.
- **File sharing**, allowing large amounts of data to be transferred directly between peers without passing through a relay or centralized storage service.
- **Private messaging**, where conversations can leave no persistent trace on public relays, not even encrypted public events that could potentially be collected today and decrypted in the future if their encryption is later compromised. This is commonly referred to as a **harvest-now, decrypt-later** attack.

## Room keys and identities

Each participant has its own Nostr identity, represented by a signer. Participants also share a room key pair, which identifies the room and allows them to prove that they belong to it.

You can use an existing `NostrSigner` or create a temporary identity:

```java
NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
```

Generate a random room key pair once:

```java
NostrKeyPair newRoomKeyPair = new NostrKeyPair();
String sharedRoomNsec = newRoomKeyPair.getPrivateKey().asBech32();
```

Your only responsibility is to distribute this key securely to the participants that should be able to join the room. Depending on your use case, this could be done in several ways, for example:

- sending it through encrypted Nostr DMs
- sharing it through an invitation link
- publishing it in plain text in a Nostr event for public rooms
- distributing it through your own centralized application backend
- showing it to the user as a QR code
- or using any other application-specific mechanism

Anyone who obtains the room key can join. Each participant loads the shared key into its own room key pair:

```java
NostrKeyPair roomKeyPair = new NostrKeyPair(
    NostrPrivateKey.fromBech32(sharedRoomNsec));
```

Keep the room key separate from personal account keys. See [room access](security.md#rtc-room-access) for key handling guidance.

!!! tip
    Every room is many-to-many. If you want a one-to-one room, simply share the room key with a single counterpart.

## Settings and room setup

There are also a few settings that need to be provided through `RTCSettings`. Luckily, most defaults are sensible, so you can start with:

```java
RTCSettings.getDefault("app name", "protocol name");
```

Make sure to replace `"app name"` and `"protocol name"` with values appropriate for your application. These values are used during discovery to filter out peers that are using a different application, protocol, or incompatible version.

Set the signaling relays and, if needed, the number of direct neighbors each peer maintains:

```java
RTCSettings settings = RTCSettings.getDefault("my-app", "chat-v1")
    .withSignalingRelays(List.of("wss://relay.ngengine.org"))
    .withMaxDirectPeers(8);
```

Use the same settings values on every participant. Each `with…` method returns a new settings object, so keep its result. By default, each peer maintains up to 16 direct neighbors; `withMaxDirectPeers` accepts 2 to 64. A lower value means fewer direct connections and more forwarding. It does not limit room membership.

Create the signaling pool, local peer and room with the same settings object:

```java
NostrPool signalingPool = new NostrPool();
NostrRTCLocalPeer local = new NostrRTCLocalPeer(
    settings, signer, roomKeyPair, null);
NostrRTCRoom room = new NostrRTCRoom(
    settings, local, roomKeyPair, signalingPool, null);
```

The signaling pool connects the configured relays through `ensureRelay` and can be shared by rooms using the same relays. The local peer gets a session ID for this instance. The two `null` arguments leave TURN unconfigured, more details on this will be covered in the following sections.

## Discovery and connection

As soon as you start a room with:

```java
room.start();
```

Nostr4j begins handling peer discovery and announcements automatically.

You can add listeners to the room to react to discovery events. For example, when a peer becomes available, you might show it in a peer list or lobby.

Make sure to register your listeners before starting the room so that you do not miss the initial events.

For example, receive a UTF-8 message and greet each peer when its socket becomes available:

```java
room.addMessageListener((peer, socket, channel, data, turn) -> {
    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    System.out.println(new String(bytes, StandardCharsets.UTF_8));
});

room.addPeerSocketAvailableListener((peer, socket) -> {
    byte[] greeting = "hello".getBytes(StandardCharsets.UTF_8);
    room.send(peer, ByteBuffer.wrap(greeting))
        .catchException(error -> {
            System.err.println("Could not send greeting: " + error);
        });
});

// Use addDisconnectionListener to react when a peer's socket closes.

room.start().await();
```


Once a peer becomes available, you can start sending bytes to it with:

```java
room.send(peer, ByteBuffer.wrap(message));
```

If you want to broadcast a message to all peers in the room, use:

```java
room.broadcast(ByteBuffer.wrap(message));
```

!!! tip
    In some cases, broadcasting is more efficient than sending the same message individually to every peer, so prefer `broadcast()` when appropriate.


!!! note
    The room sends and receives raw `ByteBuffer` values. Nostr4j intentionally does not perform any application-level serialization, so it is up to your application to define a format and handle serialization and deserialization accordingly.

    Both send calls are asynchronous. Handle failures from their returned tasks. A completed send does not prove that another application has processed the payload; have it reply if you need that confirmation.



### Named channels

Create a named channel when a connection carries several kinds of messages, usually in the socket listener:

```java
room.createChannel(peer, "updates");
room.send("updates", peer, ByteBuffer.wrap(message));
```

In the message listener, check `channel.getName()` to choose a handler. Channels created with this overload are ordered and reliable. Other `createChannel` overloads let you choose ordering, reliability and retransmission limits.


!!! tip
    Sometimes using multiple channels can improve performances by reducing contention and allowing parallel processing of different message types.
    This is especially useful with high-throughput applications and unreliable networks.

## Unstoppable P2P with multi-hop routing

Every peer-to-peer network is different. Sometimes two peers cannot connect directly to each other, but both can connect to other peers in the room. Large P2P rooms can also reach a point where bandwidth becomes saturated, since sending the same packet independently to many peers can place a significant load on the sender.

To handle these situations, Nostr4j uses a fully transparent and automatic multi-hop routing mechanism: packets can be routed through other peers in the room and reach their destination as long as at least one viable path exists between the sender and the receiver. When using `broadcast`, participants can contribute to forwarding the message, reducing the bandwidth burden on any single node.

Every unicast packet is end-to-end encrypted, which means intermediate routing peers can forward packets without being able to read their contents.

!!! note
    All of this is handled automatically by Nostr4j. From your application's point of view, every peer appears to be directly reachable. Under the hood, Nostr4j determines whether packets should travel directly or through one or more intermediate peers, optimizes the routes and manages the forwarding process transparently.



## When everything fails, there is still a path

If every peer-to-peer path fails, including multi-hop routing, Nostr4j can fall back to TURN servers when one is configured.

Using a purpose-built protocol, Nostr4j peers can authenticate using their Nostr identities and securely route packets through a binary relay: the TURN server.

Packets remain end-to-end encrypted, so the TURN server only forwards them and cannot read their contents.

This fallback is also completely transparent to the application.

To enable it, pass the service's public URL to the local peer and a `NostrTURNPool` to the room instead of the two `null` arguments in the setup example:

```java
String turnUrl = "wss://turn.example.com/turn";
NostrTURNPool turnPool = new NostrTURNPool();

NostrRTCLocalPeer local = new NostrRTCLocalPeer(
    settings, signer, roomKeyPair, turnUrl);
NostrRTCRoom room = new NostrRTCRoom(
    settings, local, roomKeyPair, signalingPool, turnPool);
```

The endpoint is advertised to peers. Share one TURN pool across rooms to optimize resource usage.

!!! tip
    The TURN url is a `NostrRTCLocalPeer` setting, meaning every peer can have its own TURN server. Every peer will send through the counterparty's TURN server and receive through its own TURN server. You can use this behavior to let each peer choose its preferred TURN server.

See [TURN fallback server](turn-server.md) to learn how the service works and how to host it.

## Mixing and matching paths automatically

Nostr4j can automatically mix and match all of these communication paths.

A peer may initially be reached through TURN, later become reachable through another peer using multi-hop routing, and eventually establish a direct connection. Likewise, if a direct path disappears, Nostr4j can automatically downgrade to another available path.

The path used for a particular peer can therefore change over time as network conditions change.

This is fully transparent to your application: your code simply sends packets to a peer, and Nostr4j handles how those packets reach their destination.

As long as any usable path exists, Nostr4j can use it. If the current path becomes unavailable or the network topology changes unexpectedly, packets can be rerouted automatically through another available path.


!!! tip
    To exercise TURN during development, call `room.setForceTURN(true)` and later set it to `false` for automatic path selection. A `NostrRTCSocketListener` reports switches and degradation through `onRTCSocketTransportSwitch` and `onRTCSocketTransportDegraded`; transport states are `NONE`, `RTC` and `TURN`.

## Close the room

Call `room.close()` when the session ends. The signaling pool, TURN pool, signer and supplied keys remain owned by your application. Close each after its last user has finished, so one room cannot close resources used by another.

For a complete room lifecycle, see the [RTC ping demo source](https://github.com/NostrGameEngine/nostr4j/blob/gh-pages/site-demos/src/main/java/org/ngengine/site/demos/rtc/RTCPingDemo.java).
