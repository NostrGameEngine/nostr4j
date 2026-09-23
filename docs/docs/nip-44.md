---
title: NIP-44 encryption
---

# NIP-44 - Versioned encryption

NIP-44 is the default encryption algorithm behind `NostrSigner.encrypt` and `decrypt`:

```java
String payload = signer.encrypt("meet at dawn", recipient).get();
String plain = signer.decrypt(payload, sender).get();
```

For local cryptographic workflows, derive a conversation key and reuse it for messages to the same peer:

```java
byte[] conversationKey = Nip44.getConversationKey(
    myPrivateKey, theirPublicKey).get();

String payload = Nip44.encrypt("hello", conversationKey).get();
String plain = Nip44.decrypt(payload, conversationKey).get();
```

`Nip44` also provides `ByteBuffer` and byte-array variants. Protect the conversation key like other secret material, do not log it, and discard references when the conversation ends. Use the signer methods unless the lower-level key lifecycle is genuinely needed.
