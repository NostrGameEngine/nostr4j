---
title: NIP-49 encrypted keys
---

# NIP-49 - Encrypted private keys

NIP-49 encodes a private key as `ncryptsec1…` using a passphrase-based, memory-hard derivation. Use it when a local private key must be persisted.

```java
String encrypted = privateKey.asNcryptsec(passphrase).get();
NostrPrivateKey restored = NostrPrivateKey
    .fromNcryptsec(encrypted, passphrase).get();
```

The lower-level `Nip49` helpers expose synchronous and asynchronous variants plus explicit work-factor and memory-limit overloads. `Nip49.getApproximatedMemoryRequirement(logn)` lets a UI estimate the cost before starting.

An encrypted key is only as strong as its passphrase and storage controls. Do not retain the plaintext `nsec`, decrypted key or passphrase after use. Call `close()` on the restored `NostrPrivateKey`; understand that Java cannot reliably erase immutable `String` copies.
