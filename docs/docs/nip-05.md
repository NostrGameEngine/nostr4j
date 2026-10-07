---
title: Verify an internet identifier
---

# Verify an internet identifier

A profile may claim an address such as `alice@example.com`. To check it, fetch the NIP-05 record published by that domain and compare the returned public key with the profile author's key. The record comes from `/.well-known/nostr.json`:

```java
// split "alice@example.com" into [name, domain]
Nip05Identity id = Nip05.fetch("alice@example.com", Duration.ofSeconds(10)).await();

NostrPublicKey pubkey = id.getPublicKey();
id.getName();        // "alice"
id.getDomain();      // "example.com"
id.getIdentifier();  // "alice@example.com"
```

Use HTTPS for these lookups. For plain HTTP in test environments, use the `useHttps` overload.

Some domains also publish remote-signer details. Read them with `id.getNip46Data()` if you want to offer a [nip-46 connection](signers.md#connect-to-a-remote-nip-46-signer). The original response is available as `id.data`.
