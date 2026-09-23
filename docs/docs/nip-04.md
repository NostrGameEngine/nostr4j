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

These calls also work with browser and remote signers that support NIP-04. With a local private key in hand, use the `Nip04.encrypt(...)` and `Nip04.decrypt(...)` helpers directly.

Use the event kind and tags required by the protocol you are implementing. For new encrypted data, prefer [NIP-44](nip-44.md). Decryption in either format returns the payload; validating it is your app's job.
