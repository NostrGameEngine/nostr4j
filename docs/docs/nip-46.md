---
title: NIP-46 remote signing
---

# NIP-46 - Remote signing

A NIP-46 signer keeps the user's real key in a bunker and uses an application-specific transport key for encrypted RPC over Nostr.

```java
Nip46AppMetadata app = new Nip46AppMetadata()
    .setName("My app")
    .setUrl("https://app.example.com")
    .addPerm("sign_event:1");

NostrNIP46Signer signer = new NostrNIP46Signer(
    app, new NostrKeyPair());

signer.connect(BunkerUrl.parse(bunkerUrl)).get();
SignedNostrEvent signed = signer.sign(unsigned).get();
```

If the user starts from the bunker, `listen(...)` generates a `nostrconnect://` URL for a QR code and waits for approval:

```java
signer.listen(
    List.of("wss://relay.example.com"),
    url -> showQr(url.toString()),
    Duration.ofMinutes(5)).get();
```

Treat the `secret` in either URL as a pairing credential: keep it out of logs and analytics. Request the narrowest permission set, apply a finite request timeout with `setRequestsTimeout(...)`, and call `close()` when the session ends.
