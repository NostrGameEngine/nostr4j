---
title: Send a gift-wrapped message
---

# Send a gift-wrapped message

Nostr4j implements gift wrapping as defined in [NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md).

## Wrap for one recipient

With the sender's `signer` and the recipient's public key:

```java
UnsignedNostrEvent draft = new UnsignedNostrEvent()
    .withKind(14)
    .withTag("p", recipient.asHex())
    .withContent("Hello privately");

SignedNostrEvent giftWrap = Nip59.wrap(signer, draft, recipient).await();
pool.publish(giftWrap).await();
```

## Open a received wrap

On the recipient's side, use their signer and the received signed `event`:

```java
if (Nip59.isGiftWrap(event)) {
    Map<String, Object> rumor = Nip59.unwrap(signer, event).await();
    System.out.println(rumor.get("content"));
}
```

