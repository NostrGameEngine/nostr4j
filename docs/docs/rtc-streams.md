---
title: Exchange data with streams
---

# Exchange data with streams

Use `org.ngengine.nostr4j.io.NostrRTCPeerConnection` when JVM code expects an `InputStream` and an `OutputStream`. It adapts an RTC room into one ordered byte stream to a selected peer, using the same signaling, routed transport and optional TURN fallback as the room API.

## Connect both participants

Each participant needs its own `NostrPrivateKey`, the other participant's public key and a shared, non-empty `connectionId` chosen by your application. The settings' application ID, protocol ID and signaling relays must also match. The adapter derives the room key from the two identities and the connection ID; you do not distribute a separate room private key.

Run this code on both participants, swapping the local and remote keys:

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:rtc-stream"
```

The supplied private key stays owned by your application; the connection uses a private copy. This example sends `hello`, closes the outgoing direction and reads the other participant's bytes until EOF. `connect()` starts discovery and the room. It does not mean the remote stream is already ready; writes and reads may wait for the peer.

The constructor shown selects 4096-byte chunks and retains up to 16 freed chunks for reuse. To use a TURN fallback, replace `null` with a compatible `wss://` endpoint. For a new temporary identity, use `new NostrRTCPeerConnection(connectionId)` and exchange `getPeerId()` values before calling `connect()` on both sides.

## Blocking, framing and shutdown

These are ordinary blocking Java streams. Run the transfer on a worker or virtual thread, and use the [room's asynchronous message API](rtc-rooms.md) for browser or event-loop code. The adapter buffers incoming data with limits and pauses the sender when its receive queue grows.

A byte stream has no application message boundaries. Use lengths, delimiters or a framing format if you need to distinguish several messages. `flush()` sends a partially filled write chunk.

Closing the output stream flushes pending bytes and sends an ordered EOF while leaving the input direction available. The input stream returns `-1` after the remote EOF and buffered data have been read. `connection.close()` aborts the connection and releases its resources; use output close first when the other side must receive a clean end of stream.

The connection pins one remote session. If the other application restarts with a new session, create a new connection rather than assuming the old stream continues.
