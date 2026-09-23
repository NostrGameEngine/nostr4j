---
title: NIP-04 legacy encryption
---

# NIP-04 - Legacy encrypted messages

NIP-04 is retained for interoperability with older clients. Prefer NIP-44 for new protocols.

Through a signer:

```java
String ciphertext = signer.encrypt(
    "legacy message", recipient,
    NostrSigner.EncryptAlgo.NIP04).get();

String plaintext = signer.decrypt(
    ciphertext, sender,
    NostrSigner.EncryptAlgo.NIP04).get();
```

Local-key code can call `Nip04.encrypt(...)` and `Nip04.decrypt(...)` directly, but doing so couples the application to raw private-key access. Signer-based code also works with browser and remote signers.

NIP-04 only defines the ciphertext. Your application must still choose the event kind, tags and access policy, and must treat decrypted text as untrusted input.
