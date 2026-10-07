---
title: Encrypt a private message
---

# Encrypt a private message

To encrypt text for another user, pass their public key to your signer. On the receiving side, decrypt using the sender's public key. These calls use NIP-44 by default:

```java
String payload = signer.encrypt("meet at dawn", recipient).await();
String plain = signer.decrypt(payload, sender).await();
```

If you manage local keys directly, you can derive a conversation key once and reuse it for messages to the same peer:

```java
byte[] conversationKey = Nip44.getConversationKey(
    myPrivateKey, theirPublicKey).await();

String payload = Nip44.encrypt("hello", conversationKey).await();
String plain = Nip44.decrypt(payload, conversationKey).await();
```

Use the signer methods when you want the same application code to work with local keys, browser extensions and remote signers. To use byte arrays or `ByteBuffer` values, call the direct helpers instead - but then store and clean up the conversation key yourself.

Encryption produces a payload. To deliver a private message, place it in the event format required by your messaging protocol; NIP-44 itself does not assign an event kind.
