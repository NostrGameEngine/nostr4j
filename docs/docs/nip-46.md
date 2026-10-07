---
title: Pair with a remote signer
---

# Pair with a remote signer

With a remote signer, the user's account key stays on another device or service. Create a separate client key for your app, describe the permissions you need, then connect using the user's bunker URL:

```java
Nip46AppMetadata app = new Nip46AppMetadata()
    .setName("My app")
    .setUrl("https://app.example.com")
    .addPerm("sign_event:1");

NostrNIP46Signer signer = new NostrNIP46Signer(
    app, new NostrKeyPair());

signer.connect(BunkerUrl.parse(bunkerUrl)).await();
SignedNostrEvent signed = signer.sign(unsigned).await();
```

Alternatively, start pairing in your app. Show the `nostrconnect://` URL returned by `listen` as a QR code for the user to scan with their signer:

```java
signer.listen(
    List.of("wss://relay.example.com"),
    url -> showQr(url.toString()),
    Duration.ofMinutes(5)).await();
```

Keep pairing secrets out of logs and analytics. After connecting, set a request timeout with `setRequestsTimeout(...)` so an offline signer cannot leave the UI waiting indefinitely. Call `close()` when the session ends.
