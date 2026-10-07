---
title: Connect relays and publish
---

# Connect relays and publish

<a class="doc-run-button" href="../demos.html#publish">Run publishing demo</a>

To connect a relay, add it to a pool with `ensureRelay` - if you already added that URL, the existing entry is reused:

```java
NostrPool pool = new NostrPool();
pool.ensureRelay("wss://relay.ngengine.org").await();
```

You can add more URLs to the same pool. If you need to configure a relay first, construct a `NostrRelay` and pass it to `addRelay`.

If a connection drops, it is re-established automatically and any subscriptions are reopened. Keep the pool alive while the application needs those connections, then call `pool.close()`.

## Wait for publication

Sending an event doesn't yet tell you whether a relay accepted it. To wait for at least one successful acknowledgement, use `publish(signed)`; pass a policy if you need more confirmations:

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:publish-quorum"
```

This example waits for a strict majority. The available policies are:

| Policy | Required acknowledgements |
|---|---|
| `NostrPoolAnyAckPolicy` | At least one relay accepts (the default) |
| `NostrPoolQuorumAckPolicy` | More than half accept |
| `NostrPoolAllAckPolicy` | Every relay accepts |

The policy is checked whenever a relay task settles. Publication completes as soon as the condition is met, and returns the individual relay tasks as its result. Some may still be pending at that point - inspect them to see which relays accepted the event.

The return type of `publish` is `AsyncTask<List<AsyncTask<NostrMessageAck>>>`. Await the outer task to apply the policy. Awaiting all returned relay tasks afterwards waits for every relay, even when the chosen policy needs fewer acknowledgements. The older `send` method returns relay tasks directly and is deprecated.

With the built-in policies, publication succeeds as soon as the policy is satisfied, but reports failure only after every relay task has finished. For example, with `NostrPoolAllAckPolicy`, one rejection is enough to prevent success; the operation still waits for the remaining tasks before failing.

!!! note
    If the pool has no relays, publication returns an empty list without an error. No event has been sent in that case.

## Handle duplicate deliveries

The same event can arrive through several relays. To avoid handling duplicates yourself, give the subscription an `EventTracker`: seen events are discarded before they reach your listeners.

You can choose a tracker with `pool.subscribe(filter, factory)`:

| Tracker | Behavior |
|---|---|
| `ForwardSlidingWindowEventTracker` **(default)** | Tracks IDs and timestamps in a moving window, discards older entries, rejects non-current events and tunes retention to the subscription's filters |
| `NaiveEventTracker` | Remembers every distinct event ID until cleared or discarded; useful for finite reads |
| `PassthroughEventTracker` | Allows every delivery, including duplicates, without retaining event IDs |

!!! tip "Prefer the sliding window for long-lived subscriptions"
    Keep the default `ForwardSlidingWindowEventTracker` for ongoing feeds: only event identifiers are stored (not event contents), duplicates are looked up in a hash index, and old entries are discarded as the window advances. A `NaiveEventTracker` instead grows with every distinct event until you clear or discard it.

    Tuning is automatic, based on the subscription's filters.

The default tracker rejects expired events and events dated more than 30 seconds into the future before updating its window. A future-dated event cannot push ordinary events out of the tracked history. For finite archival reads that need such events, explicitly select a different tracker and validate timestamps in your application. If a custom tracker factory throws or returns `null`, creating the subscription fails; duplicate tracking is not silently disabled.

!!! note "Debugging duplicate delivery"
    For diagnostics - or where a duplicate indicates a bug - use `FailOnDoubleTracker`. On a duplicate ID, you get a `RuntimeException` that includes the stack trace recorded at the event's first encounter. IDs and stack traces are retained until cleared or discarded, so it is not suited to long-lived feeds where duplicate deliveries are normal.
