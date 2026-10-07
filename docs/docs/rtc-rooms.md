---
title: Connect and exchange data
---

# Connect and exchange data

<a class="doc-run-button" href="../demos.html#rtc">Run RTC ping demo</a>

This page shows how to exchange data between participants in a room. The example below creates a `NostrRTCRoom` that sends a greeting when a peer becomes available and prints the messages it receives.

## Create a room

Start with your participant's `signer` and a room key shared with the other participants. In the example below, `sharedRoomNsec` is the room's private key, received through your application's invitation flow. Each participant loads that same key. See [room access](security.md#rtc-room-access) if you need to create one.

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:rtc-room"
```

Use `RTCSettings.getDefault(applicationId, protocolId)` for the usual connection policy. Application and protocol IDs, signaling relays and STUN servers belong in these settings. To change a setting, call a `with…` method and keep its result - it returns a new settings object. For example, `withStunServers(List.of("stun.l.google.com:19302"))` selects one STUN server.

Create the `NostrRTCLocalPeer` with those settings, your signer and the shared room key pair. This constructor generates a session ID; use the overload taking a session ID if your app supplies one. Pass the same settings to the room. The room ensures the settings' relay URLs on the supplied `NostrPool`, and the connections proceed asynchronously. If your app opens several rooms with the same relay configuration, give them a shared pool to reuse those connections.

The local peer's TURN URL and the room's TURN pool are both `null` here. Direct WebRTC connections and peer routing work with this setup.

## Receive messages and greet new peers

Register the listeners before starting the room; the message listener is called with a `ByteBuffer`. Here we copy its remaining bytes and decode the greeting as UTF-8.

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

room.start().await();
```

Call `start()` to begin discovery and connection management. Once the room has a logical socket for a discovered peer, the socket listener fires - create channels and queue sends there while transport negotiation continues. The example waits for startup with `await()`; an asynchronous application can compose the returned task instead.

The `peer` passed to a listener is also the object you use to address that participant. Keep the peers your application needs and use `addDisconnectionListener` to react when their sockets are closed. Both direct and routed messages reach the same listener.

## Send on the default channel or create your own

The greeting uses the default channel. Once you have a peer, you can send any application payload in the same way, or broadcast an update to the room:

```java
room.send(peer, ByteBuffer.wrap(message))
    .catchException(error -> {
        System.err.println("Send failed: " + error);
    });

room.broadcast(ByteBuffer.wrap(announcement))
    .catchException(error -> {
        System.err.println("Broadcast failed: " + error);
    });
```

Both calls are asynchronous; handle failures according to your application protocol. If you need confirmation that another application has processed an action, have it send a reply - the transport's completion alone cannot tell you that.

Sends can remain queued for a limited time while connectivity recovers. Set that wait with `RTCSettings.withQueuedSendTimeout(Duration)`; the default is 30 seconds. Each queued send keeps its original deadline across retries and transport switches; reconnecting does not give an old message a fresh timeout. Closing the room cancels pending sends. For fast-changing state, such as player positions, send a fresh update after recovery rather than replaying expired state.

Use named channels when a connection carries several kinds of messages. Create the channel for the peer before sending on it, usually in the listener shown above:

```java
room.createChannel(peer, "updates");
room.send("updates", peer, ByteBuffer.wrap(message));
```

In the receiving listener, check `channel.getName()` to choose a handler. Channels created with this overload are ordered and reliable. To choose ordering, reliability and retransmission limits for your traffic, use the other `createChannel` overloads.

## Add an optional TURN fallback

To use a Nostr4J TURN service, pass its public URL to the local peer and a `NostrTURNPool` to the room:

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:rtc-turn"
```

Use these constructors in place of the earlier pair, then register the same listeners and start the room. With this setup the endpoint is advertised to peers, and fallback and recovery are handled for you.

TURN receive channels are opened when fallback is selected, even before this participant sends its first message. A lost physical connection can be replaced while the logical channel is retained; explicit channel or pool closure ends that channel.

Share one TURN pool across several rooms, even with different application, protocol and session IDs. See [Host a fallback server](turn-server.md) to run your own endpoint.

To exercise the configured fallback during development, call `room.setForceTURN(true)`, then set it back to `false` to restore automatic path selection. To watch transport switches and degradation, register a `NostrRTCSocketListener` - its `onRTCSocketTransportSwitch` and `onRTCSocketTransportDegraded` callbacks tell you what changed. Its transport states are `NONE`, `RTC` and `TURN`.

## Close the room when the session ends

Call `room.close()` when your application leaves the session. The signaling pool, TURN pool and supplied keys stay owned by your application. Close each only after its last user has finished, so one room cannot close resources another still needs.

For the room lifecycle in a working application, see the [RTC ping demo source](https://github.com/NostrGameEngine/nostr4j/blob/gh-pages/site-demos/src/main/java/org/ngengine/site/demos/rtc/RTCPingDemo.java). For larger groups, continue with [onion routing](onion-routing.md). If your JVM code works with `InputStream` and `OutputStream`, use the [peer connection adapter](rtc-streams.md).
