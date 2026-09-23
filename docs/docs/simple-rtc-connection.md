---
title: Simple P2P streams
---

# Simple P2P streams

Nostr4j exposes a simple Java I/O abstraction on top of its p2p capabilities, allowing developers to use familiar `InputStream` and `OutputStream` APIs for peer-to-peer communication.

For rooms, channels and routing, see [P2P connections](p2p-overview.md).

## Connect two peers

1. Choose an arbitrary connection ID, in this example `my-connection`.
2. Initialize the `NostrRTCPeerConnection` with the chosen connection ID on both sides.
3. Exchange the two peer IDs out-of-band, for example through your app backend.
4. Call `connect()` on both sides with the peer ID of the other side.

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
