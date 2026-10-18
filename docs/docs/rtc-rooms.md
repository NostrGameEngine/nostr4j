---
title: Connect and exchange data
---

# Connect and exchange data

<a class="doc-run-button" href="../demos.html#rtc">Run RTC ping demo</a>

Use `NostrRTCRoom` to exchange asynchronous messages with one or more participants. For a two-peer Java stream, use [Simple P2P streams](simple-rtc-connection.md).

## Create or join a room

To create or join a room, you need a Nostr signer for your participant. Use the user's signer or a key-pair signer with a temporary identity created for this session.

You also need a room key: a randomly generated private key shared among the room's participants. Generate it once per room, then share it through your own application flow, such as your backend or a private invitation link. See [Create or join a room](p2p-overview.md#create-or-join-a-room) for the key-generation example.

In the example below, `signer` identifies the participant and `sharedRoomNsec` contains the shared room key. All participants reconstruct the same room key pair and use matching application and protocol IDs.

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:rtc-room"
```

The room uses the signaling relays in `RTCSettings`. The local peer gets a session ID automatically. The two `null` arguments leave TURN fallback disabled.

!!! tip "Reuse relay connections"
    Give multiple rooms the same `NostrPool` to reuse relay connections and reduce the total number of connections.

!!! note "Changing settings"
    Settings are immutable. Keep the result of each `with...` call and pass the same settings to the local peer and room. See [connection settings](rtc-tuning.md) for budgets and retries.

## Receive messages and greet new peers

Add listeners before starting the room:

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

The socket listener gives you the peer to address. Transport setup can still be in progress; sends wait for connectivity within their timeout. Direct and routed messages reach the same message listener.

Use `addDisconnectionListener` when your app needs to react to a peer leaving.

## Send on the default channel or create your own

Send to one peer or broadcast to the room:

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

!!! note "Delivery and replies"
    Handle failed send tasks. If you need confirmation that the other app processed a message, have it send a reply.

Queued sends wait up to 30 seconds by default. Set this with `RTCSettings.withQueuedSendTimeout(Duration)`.

Use named channels to separate traffic such as chat and game updates. Create the channel for the peer before sending:

```java
room.createChannel(peer, "updates");
room.send("updates", peer, ByteBuffer.wrap(message));
```

Check `channel.getName()` in your message listener to choose a handler. This overload creates an ordered, reliable channel; other overloads let you change delivery options.

## Add an optional TURN fallback

Give the local peer a TURN URL and the room a TURN pool:

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:rtc-turn"
```

Use these constructors instead of the earlier pair, then add the same listeners and start the room. The room advertises the endpoint and manages fallback automatically.

!!! tip "Test the fallback"
    Call `room.setForceTURN(true)` during development. Set it back to `false` to restore automatic path selection.

See [Host a fallback server](turn-server.md) to run a compatible service.

## End-to-end encryption and privacy

Messages sent to a specific peer are encrypted end to end. Only the destination peer can read their contents, whether the path is direct WebRTC, a TURN server or [multi-hop routing](onion-routing.md) through other peers. Forwarding peers and TURN servers cannot decrypt those payloads. Broadcast messages are shared with the room's members.

Connection setup is protected too. Offers, answers and ICE connection details sent through Nostr relays are encrypted for the intended peer. The relay and other room members cannot read their contents. Topology updates are encrypted for members holding the shared room key.

!!! tip "Keep the user's identity separate from signaling"
    Use a fresh, temporary key-pair signer for each participant's room session instead of their account signer. Generate a separate random room key as well. This keeps the user's account public key out of signaling events and metadata, reducing direct links to their identity; network metadata can still allow correlation.

    Once the RTC connection is established, send the user's identity directly to the intended peer over the encrypted connection. For example, send an event signed by the user's account key that binds the temporary peer key to the current session and a challenge supplied by the recipient. The recipient can verify the signature and challenge before associating that peer with the account.

## Close the room

Call `room.close()` when leaving the session.

!!! note "Shared resources"
    Your app owns the supplied signaling pool, TURN pool and keys. Close them after their last user has finished.

Continue with [multi-hop routing](onion-routing.md) for larger groups, or see the [RTC ping demo source](https://github.com/NostrGameEngine/nostr4j/blob/gh-pages/site-demos/src/main/java/org/ngengine/site/demos/rtc/RTCPingDemo.java).
