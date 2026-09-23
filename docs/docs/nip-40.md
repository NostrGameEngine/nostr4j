---
title: NIP-40 Expiration
---

# NIP-40 - Expiring events

Some events should stop being served after a while: ephemeral announcements, temporary offers, presence updates. NIP-40 is a single `expiration` tag with a unix timestamp, and Nostr4J exposes it directly on the event builder:

```java
UnsignedNostrEvent ev = new UnsignedNostrEvent()
    .withKind(1)
    .withContent("Live in one hour, ignore this afterwards")
    .withExpiration(Instant.now().plus(Duration.ofHours(2)));
```

Reading it back from a signed event:

```java
Instant expiresAt = signed.getExpiration();   // null if the event has no expiration
```

Relays that support NIP-40 drop expired events instead of serving them. Relays that don't will keep serving them, so treat expiration as a hint to well-behaved relays, not as deletion - for that, see [NIP-09](nip-09.md).

## Where next

- [NIPs index](nips.md)
