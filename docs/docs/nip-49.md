---
title: Encrypt a private key
---

# Encrypt a private key

Before saving a local private key, encrypt it with a passphrase. The resulting `ncryptsec1…` string uses the NIP-49 format and can be restored later:

```java
String encrypted = privateKey.asNcryptsec(passphrase).await();
NostrPrivateKey restored = NostrPrivateKey
    .fromNcryptsec(encrypted, passphrase).await();
```

If you need to choose the derivation work factor or memory limit, use the `Nip49` helpers directly. You can estimate the memory needed with `Nip49.getApproximatedMemoryRequirement(logn)` before starting.

Choose a strong passphrase and protect the stored file. Close the restored private key after use. Avoid making plaintext `String` copies: closing the key cannot erase those copies. See [key storage](keys.md) for the surrounding account lifecycle.
