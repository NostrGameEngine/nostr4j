---
title: Read legacy encrypted messages
---

# Read legacy encrypted messages

When working with an older protocol that expects NIP-04 encryption, select it explicitly on the signer:

```java
String ciphertext = signer.encrypt(
    "legacy message", recipient,
    NostrSigner.EncryptAlgo.NIP04).await();

String plaintext = signer.decrypt(
    ciphertext, sender,
    NostrSigner.EncryptAlgo.NIP04).await();
```

!!! tip
    For new encrypted data, prefer [NIP-44](nip-44.md).
