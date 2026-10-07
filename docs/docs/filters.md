---
title: Filters
---

# Filters

Use a `NostrFilter` to describe the events you want: by author, kind, time range, or tags. To select separate groups of events, use several filters.

## Compose a filter

For notes from one author over the last week:

```java
NostrFilter notes = new NostrFilter()
    .withKind(1)
    .withAuthor(author)
    .since(Instant.now().minus(Duration.ofDays(7)))
    .limit(50);
```

Here, `author` is a `NostrPublicKey`. You can also pass its hexadecimal representation to `withAuthor(String)`. Use hexadecimal event IDs with `withId`.

| Method | Selects or configures |
|---|---|
| `withId(id)` | An event ID |
| `withAuthor(author)` | An author's public key |
| `withKind(kind)` | An event kind |
| `withTag(name, values...)` | Accepted values for a tag |
| `since(instant)` | An inclusive lower bound on creation time |
| `until(instant)` | An inclusive upper bound on creation time |
| `limit(count)` | The requested maximum number of historical results |

An unset field adds no restriction. An empty `NostrFilter` therefore has no selection criteria; add the fields your application needs before using it.

## Combine criteria and alternatives

Different fields in one filter are combined with **AND**. Multiple values within one field are alternatives, combined with **OR**:

```java
NostrFilter activity = new NostrFilter()
    .withAuthor(alice)
    .withAuthor(bob)
    .withKind(1)
    .withKind(6);
```

This selects notes or reposts authored by Alice or Bob.

Repeated calls to `withId`, `withAuthor` and `withKind` append values. Calls to `since`, `until` and `limit` replace the previous value.

To express separate combinations, create separate filters:

```java
NostrFilter aliceNotes = new NostrFilter()
    .withAuthor(alice)
    .withKind(1);

NostrFilter bobReposts = new NostrFilter()
    .withAuthor(bob)
    .withKind(6);

List<NostrFilter> filters = List.of(aliceNotes, bobReposts);
```

When included in the same Nostr request, these filters are alternatives: an event needs to satisfy at least one of them. Keep each combination in its own filter instead of merging all the authors and kinds into one.

## Select by tags

!!! tip
    Pass the tag name without the `#` prefix used in the wire format.

```java
NostrFilter topics = new NostrFilter()
    .withKind(1)
    .withTag("t", "java", "nostr")
    .withTag("p", mentionedUser.asHex());
```

This selects notes with a `t` tag whose value is `java` or `nostr`, and a `p` tag referencing `mentionedUser`. Different tag names are combined with AND; values for the same name are alternatives.

Tag filtering compares the first value after the tag name. For `["e", "<event-id>", "<relay-url>"]`, use `withTag("e", eventId)` to match the event reference. The relay URL is not the value selected by that filter.

!!! note
    Unlike the author and kind methods, `withTag` replaces the accepted values for that name. To select several hashtags, pass them in one call; calling `withTag("t", "java")` and then `withTag("t", "nostr")` leaves only `nostr`.
    Filters work only with the single-letter tags indexed by the relays.


## Set time bounds and limits

Use `Instant` for both ends of a time range:

```java
Instant end = Instant.now();
NostrFilter recent = new NostrFilter()
    .withKind(1)
    .since(end.minus(Duration.ofDays(1)))
    .until(end)
    .limit(100);
```

Both boundaries are inclusive and are serialized as Unix seconds.

`limit` caps only the historical results each relay returns. It does not time out the subscription, and it does not cap events that arrive after you subscribe. A relay may return fewer results, and each relay applies the limit independently.

## Search event content

Use `NostrSearchFilter` from `org.ngengine.nostr4j.nip50` to add a text query alongside the same author, kind, tag and time criteria:

```java
NostrSearchFilter search = new NostrSearchFilter()
    .search("nostr development")
    .withKind(1)
    .withAuthor(author)
    .withTag("t", "java")
    .since(Instant.now().minus(Duration.ofDays(30)))
    .limit(20);
```

The query is sent in the `search` field. Only relays that support NIP-50 will process it.

Calling `search` again replaces the query. A null or empty query omits the search field. Keep ordinary selection criteria in their filter fields rather than embedding them in the query text.

## Match events locally

Use `matches(event)` to check one event. For repeated checks, `prepare()` captures the filter's current criteria as a reusable predicate:

```java
NostrFilter filter = new NostrFilter()
    .withKind(1)
    .withTag("t", "java");

java.util.function.Predicate<SignedNostrEvent> accepts = filter.prepare();
boolean matches = accepts.test(event);
```

Later edits to `filter` do not change `accepts`; prepare another predicate when the criteria change. Both `matches(event)` and the prepared predicate check each event with count zero. They do not keep a running result count for `limit`. To enforce that limit locally, track the number of matches and pass it to `matches(event, count)`.

The default matches only the first value after each tag name, as relay filters do. `prepare(true)` also checks the later values in each tag row for application-specific local matching; it does not change what a relay indexes. Local matching does not verify signatures.
