---
title: Event stores
---

# Event stores

Use an `EventStore` when your application needs to keep event objects and retrieve them later with filters.

## Choose a store

| Store | Retention |
|---|---|
| `InMemoryEventStore` | Keeps strong references to every event added, for the lifetime of the store |
| `WeakInMemoryEventStore` | Keeps weak references, allowing the garbage collector to reclaim events no longer strongly referenced elsewhere |

Both are in-memory. They do not persist events across application restarts, verify signatures or deduplicate insertions.

!!! note "Weak references are not durable storage"
    With `WeakInMemoryEventStore`, an event may disappear from later queries after garbage collection. Cleared references are removed while querying. Use it only when missing cached data is acceptable; it does not provide a fixed retention period or a size limit.

## Add and retrieve events

```java
EventStore store = new InMemoryEventStore();
store.addEvent(event);

NostrFilter notes = new NostrFilter()
    .withKind(1)
    .withAuthor(author)
    .limit(50);

List<SignedNostrEvent> results = store.getEvents(List.of(notes));
```

To query an `EventStore`, pass one or more `NostrFilter` objects - the same way you would query a relay - and you get back a list of matching events.

To append matches to an existing list, pass it as the second argument:

```java
List<SignedNostrEvent> results = new ArrayList<>();
store.getEvents(List.of(notes), results);
```


Inserting an event does not verify it. Apply the [event validation](events.md#verify-an-event) required by your application before storing external data.
