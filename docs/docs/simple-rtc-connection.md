---
title: Simple P2P streams
---

# Simple P2P streams

Use `org.ngengine.nostr4j.io.NostrRTCPeerConnection` to connect Java `InputStream` and `OutputStream` code between two peers. Reads and writes can block while waiting for the other participant, so run them on a worker or virtual thread.

For rooms, channels and routing, see [P2P connections](p2p-overview.md).

## Connect two peers

1. Choose an arbitrary connection ID, in this example `my-connection`.
2. Initialize the `NostrRTCPeerConnection` with the chosen connection ID on both sides.
3. Exchange the two peer IDs out-of-band, for example through your app backend.
4. Call `connect()` on both sides with the peer ID of the other side. Both sides also need matching application/protocol settings and signaling relays; the constructors below use the defaults.

```java
try (NostrRTCPeerConnection connection = new NostrRTCPeerConnection("my-connection")) {
    String myPeerNpub = connection.getPeerId().asBech32();
    // Send myPeerNpub to the other peer; obtain remotePeerNpub from that peer.
    connection.connect(NostrPublicKey.fromBech32(remotePeerNpub));

    InputStream input = connection.getInputStream();
    OutputStream output = connection.getOutputStream();
    // Read and write as with any Java stream.
    output.close(); // Send EOF when the conversation is finished.
}
```

## Persistent peer identities

With persistent peer identities, you can reconnect without exchanging peer IDs each time. Both peers must use the same `connection ID`, unique to their connection and hard to guess. Reusing an ID can cause connections from other instances of your app, or other apps using the same identities, to interfere.

Here, `savedNsec` is the local private key, `knownRemotePeerNpub` is the other participant's public key and `sharedConnectionId` is the ID agreed by both applications. Keep private keys out of logs and invitations.

```java
NostrPrivateKey localPrivateKey = NostrPrivateKey.fromBech32(savedNsec);
try (NostrRTCPeerConnection connection =
        new NostrRTCPeerConnection(localPrivateKey, sharedConnectionId)) {
    connection.connect(NostrPublicKey.fromBech32(knownRemotePeerNpub));
    InputStream input = connection.getInputStream();
    OutputStream output = connection.getOutputStream();
    // Read and write as usual.
    output.close(); // Send EOF when finished.
} finally {
    localPrivateKey.close();
}
```

Closing the output stream flushes buffered bytes and sends EOF without closing the input direction. Closing the connection aborts it and releases resources. The connection pins the remote session; if that participant restarts, open a new connection. See [stream configuration and shutdown](rtc-streams.md) for explicit settings, framing and TURN fallback.
