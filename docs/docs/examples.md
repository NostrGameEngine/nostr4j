---
title: Examples
description: Copyable Nostr4J examples checked against the 0.3.1 source API.
---

# Examples

## Runnable quick start

The [homepage example](../index.md#quick-start) opens a filtered subscription, signs and publishes a public kind-1 note, then briefly keeps the process alive so the listener can print any matching notes. It creates a temporary keypair, with no wallet or browser extension required.

```java
NGEPlatform.set(new JVMAsyncPlatform());

NostrKeyPair keys = new NostrKeyPair();
NostrPool pool = new NostrPool();
NostrSubscription subscription = null;
try {
    pool.ensureRelay("wss://relay.ngengine.org").await();

    NostrFilter filter = new NostrFilter();
    filter.withKind(1);
    filter.withAuthor(keys.getPublicKey());

    subscription = pool.subscribe(filter);
    subscription.addEventListener((sub, event, stored) ->
        System.out.println("Received: " + event.getContent())
    );
    AsyncTask.awaitAll(subscription.open());

    UnsignedNostrEvent draft = new UnsignedNostrEvent();
    draft.withKind(1);
    draft.withContent("Hello from Nostr4J!");

    NostrKeyPairSigner signer = new NostrKeyPairSigner(keys);
    SignedNostrEvent note = signer.sign(draft).await();
    AsyncTask.awaitAll(pool.publish(note));
    System.out.println("Published: " + note.getId());

    // Keep this standalone program alive briefly to receive the note.
    Thread.sleep(5_000);
} finally {
    if (subscription != null) subscription.close();
    pool.close();
    keys.close();
}
```

This is the body of a compiled and tested JVM example, without the surrounding class and imports. Put it in a method that declares `throws Exception`, after adding the desktop dependencies shown in [Getting started](getting-started.md). Initialize `NGEPlatform` only once in your application. The short pause keeps this standalone example alive long enough to print subscription events; a long-running application would keep the pool open for its own lifetime. The homepage's Run Example button uses the equivalent browser demo compiled with TeaVM. Notes are public; do not put secrets in their content. This example publishes a regular kind-1 note with a fresh temporary identity.

The [write-and-publish demo](../demos.md#publish) runs the same compiled Java code with text you enter. It publishes to `wss://relay.ngengine.org`, shows the event ID and waits for the matching subscription event as an active return-path check.

In the current platform API, `AsyncTask.await()` waits for a result. On TeaVM, call it from a platform executor so the compiler can suspend the Java task without blocking the browser. Do not call a suspending Java method directly from a JavaScript bridge callback.

The snippets below are smaller recipes, not standalone programs. Initialize the platform first; manage keys, subscriptions, pools and wallets in the application lifecycle.


## 1. Fetch recent notes from a relay

```java
// Source: nostr4j/src/main/java/org/ngengine/nostr4j/NostrPool.java
NostrPool pool = new NostrPool();
try {
    pool.ensureRelay("wss://relay.ngengine.org").await();

    NostrFilter f = new NostrFilter().withKind(1).limit(10);
    List<SignedNostrEvent> notes = pool.fetch(f, 10, true, Duration.ofSeconds(10)).await();
    for (SignedNostrEvent n : notes) {
        System.out.println(n.getPubkey() + ": " + n.getContent());
    }
} finally {
    pool.close();
}
```

## 2. Publish a signed note and evaluate quorum

```java
// Source: nostr4j/src/main/java/org/ngengine/nostr4j/NostrPool.java
NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
SignedNostrEvent note = signer.sign(
    new UnsignedNostrEvent().withKind(1).withContent("hello nostr")
).await();

List<AsyncTask<NostrMessageAck>> acks = pool.publish(note);

// Apply the reducer as the tasks settle. PENDING may later become SUCCESS.
NostrMessageAck.Status aggregate = NostrPoolQuorumAckPolicy.get().apply(acks);
```

## 3. Live subscription with dedup tracker

```java
// Source: nostr4j-demo/src/test/java/org/ngengine/nostr4j/cliclient/NostrCli.java
NostrSubscription sub = pool
    .subscribe(new NostrFilter().withKind(1).limit(20), () -> new NaiveEventTracker())
    .addEventListener((s, event, stored) -> System.out.println(event.getContent()));
List<AsyncTask<NostrMessageAck>> openAcks = sub.open();
// ... later:
List<AsyncTask<NostrMessageAck>> closeAcks = sub.close();
```

## 4. NWC connect + balance

```java
// Source: nostr4j/src/main/java/org/ngengine/wallets/nip47/NWCWallet.java
NWCUri uri = new NWCUri("nostr+walletconnect://…"); // from your wallet app
NWCWallet wallet = new NWCWallet(pool, uri);
try {
    wallet.waitForReady().await();

    long balanceMsats = wallet.getBalance(null).await();
    System.out.println("balance: " + balanceMsats + " msats");
} finally {
    wallet.close();
}
```

## 5. NWC pay an invoice

```java
// Source: nostr4j/src/main/java/org/ngengine/wallets/nip47/NWCWallet.java
PayResponse res = wallet.payInvoice("lnbc…", null, Instant.now().plusSeconds(60)).await();
System.out.println("paid, preimage: " + res.preimage());
```

## 6. Join an RTC room and send a message

```java
// Source: nostr4j-demo/src/test/java/org/ngengine/nostr4j/rtc/TestNostrRTC.java
NostrKeyPair roomKeyPair = new NostrKeyPair(roomPrivKey); // shared by all room members
NostrKeyPairSigner signer = NostrKeyPairSigner.generate();

NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
    signer, RTCSettings.PUBLIC_STUN_SERVERS,
    "my-app", "my-protocol-v1", roomKeyPair, null /* turn url */
);
NostrTURNPool turnPool = new NostrTURNPool();
NostrRTCRoom room = new NostrRTCRoom(
    RTCSettings.DEFAULT, localPeer, roomKeyPair, signalingPool, null, turnPool
);

room.addPeerSocketAvailableListener((peer, socket) -> {
    room.createChannel(peer, "chat");
    room.send("chat", peer, ByteBuffer.wrap("hello".getBytes()));
});
room.addMessageListener((peer, socket, channel, buf, isTurn) -> {
    byte[] b = new byte[buf.remaining()];
    buf.get(b);
    System.out.println("got: " + new String(b) + " via " + (isTurn ? "TURN" : "WebRTC"));
});
room.start().await();
```

## 7. Force TURN (skip direct WebRTC)

```java
// Source: nostr4j/src/main/java/org/ngengine/nostr4j/rtc/NostrRTCRoom.java
room.setForceTURN(true); // all traffic via TURN relay, e.g. behind restrictive NATs
```

## 8. Onion-routed unicast across a large room

With many members the room keeps only a bounded set of direct links (`RTCSettings.withMaxDirectPeers`), and `room.send` automatically onion-routes to peers you share no direct link with - same API, no special code:

```java
// Source: nostr4j/src/main/java/org/ngengine/nostr4j/rtc/NostrRTCRoom.java
//         + NIP-DC.md §15 (dc4 routed rooms)
RTCSettings settings = RTCSettings.DEFAULT.withMaxDirectPeers(2); // small graph -> routing kicks in
NostrRTCRoom room = new NostrRTCRoom(settings, localPeer, roomKeyPair, signalingPool, null, turnPool);

// identical send call: the room resolves the best path (direct / TURN / onion-routed)
room.send("chat", farPeer, ByteBuffer.wrap("hello across the mesh".getBytes()));
```

## 9. Blossom upload

```java
// Source: nostr4j/src/main/java/org/ngengine/blossom4j/BlossomPool.java
BlossomPool blossom = new BlossomPool(signer); // NostrSigner authorizes uploads
blossom.ensureEndpoint(new BlossomEndpoint("https://blossom.example.com"));

BlobDescriptor blob = blossom.upload(ByteBuffer.wrap(imageBytes), "photo.png").await();
System.out.println("sha256: " + blob.getSha256());
ByteBuffer back = blossom.get(blob.getSha256()).await();
```
