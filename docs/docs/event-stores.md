---
title: Event stores
---

# Event stores

An event store offers a convenient way to store events and retrieve them using the same filters you use with relays.

Stores are also used for [caching](event-cache.md).

## In-Memory Event Store

```java
NostrEventStore store = new NostrInMemoryEventStore(1000);
store.addEvent(event).await();

NostrFilter notes = new NostrFilter()
    .withKind(1)
    .withAuthor(author)
    .limit(50);

List<SignedNostrEvent> results = store.getEvents(List.of(notes)).await();
```

A fast, non-persistent store that keeps events in memory.

### Weak In-Memory Event Store

```java
NostrEventStore store = new NostrWeakInMemoryEventStore(1000);
store.addEvent(event).await();

List<SignedNostrEvent> results = store.getEvents(
    List.of(new NostrFilter().withKind(1).limit(50))).await();
```

Works like the in-memory event store, but allows the garbage collector to release events when they are no longer referenced elsewhere in your app.

## VStore Event Store

```java
VStore backing = NGEPlatform.get().getDataStore("my-app", "nostr-events");
NostrEventStore store = new NostrVStoreEventStore(backing, 200);
store.addEvent(event).await();
SignedNostrEvent saved = store.get(event.getId()).await();
```

A persistent event store that saves events in the virtual VStore file system. Use it when your app needs to keep events across restarts and sessions.
