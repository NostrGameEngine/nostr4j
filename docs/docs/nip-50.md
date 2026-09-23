---
title: NIP-50 Search
---

# NIP-50 - Search

NIP-50 adds full-text search to filters. `NostrSearchFilter` extends `NostrFilter`, so every method you know (`withKind`, `withAuthor`, `since`, `limit`, …) works, plus `search(query)`:

```java
NostrSearchFilter filter = new NostrSearchFilter()
    .search("nostr4j")
    .withKind(1)
    .limit(20);

List<SignedNostrEvent> hits =
    pool.fetch(filter, 20, Duration.ofSeconds(10)).get();
```

It works with `subscribe` too, exactly like a normal filter. One condition: **the relay has to support NIP-50**. Relays that don't will ignore the search term (or reject the filter), so pick relays that advertise search if this matters to your app.

## Where next

- [Fetch & subscribe](fetch-subscribe.md) - filters, fetch policies
- [NIPs index](nips.md)
