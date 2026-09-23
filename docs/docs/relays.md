---
title: Connect relays and publish
---

# Connect relays and publish

<a class="doc-run-button" href="../demos.html#publish">Run publishing demo</a>

To connect a relay, add it to a pool with `ensureRelay`. If you already added that URL, the existing entry is reused:

```java
NostrPool pool = new NostrPool();
pool.ensureRelay("wss://relay.ngengine.org").await();
```

You can add more URLs to the same pool. If you need to configure a relay first, construct a `NostrRelay` and pass it to `addRelay`.

If a connection drops, it is re-established automatically and any subscriptions are reopened. Keep the pool alive while the application needs those connections, then call `pool.close()`.

## Wait for publication

Sending an event doesn't yet tell you whether a relay accepted it. To wait for at least one successful acknowledgement, await `publish(signed)`; pass a policy if you need more confirmations:

```java
AsyncTask<List<AsyncTask<NostrMessageAck>>> publication =
    pool.publish(signed, NostrPoolQuorumAckPolicy.get());
publication.await();
```

This example waits for a strict majority. The available policies are:

| Policy | Required acknowledgements |
|---|---|
| `NostrPoolAnyAckPolicy` | At least one relay accepts (the default) |
| `NostrPoolQuorumAckPolicy` | More than half accept |
| `NostrPoolAllAckPolicy` | Every relay accepts |

The policy is checked whenever a relay task settles. Publication completes as soon as the condition is met, and returns the individual relay tasks as its result. Some may still be pending at that point, so inspect them to see which relays accepted the event.

Publication succeeds as soon as the policy is satisfied, but reports failure only after every relay task has finished. For example, with `NostrPoolAllAckPolicy`, one rejection is enough to prevent success; the operation still waits for the remaining tasks before failing.

!!! note
    If the pool has no relays, publication returns an empty list without an error. No event has been sent in that case.

## Handle duplicate deliveries

The same event can arrive through several relays. To avoid handling duplicates yourself, give the subscription an `EventTracker`: seen events are discarded before they reach your listeners.

You can choose a tracker with `pool.subscribe(filter, factory)`:

| Tracker | Behavior |
|---|---|
| `ForwardSlidingWindowEventTracker` **(default)** | Tracks IDs and timestamps in a moving window, discards older entries and tunes retention to the subscription's filters |
| `NaiveEventTracker` | Remembers every distinct event ID until cleared or discarded; useful for finite reads |
| `PassthroughEventTracker` | Allows every delivery, including duplicates, without retaining event IDs |

!!! tip "Prefer the sliding window for long-lived subscriptions"
    Keep the default `ForwardSlidingWindowEventTracker` for ongoing feeds: it stores only event identifiers, not event contents. It looks up duplicates in a hash index and discards old entries as the window advances, keeping memory use bounded. A `NaiveEventTracker` instead grows with every distinct event until you clear or discard it.

    Tuning is automatic, based on the subscription's filters.

!!! note "Debugging duplicate delivery"
    Use `FailOnDoubleTracker` for diagnostics or when a duplicate indicates a bug. On a duplicate ID, you get a `RuntimeException` that includes the stack trace recorded at the event's first encounter. IDs and stack traces are retained until cleared or discarded, so it is not suited to long-lived feeds where duplicate deliveries are normal.
