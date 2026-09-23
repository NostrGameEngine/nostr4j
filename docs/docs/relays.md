---
title: Relays and connections
---

# Relays & connections

Relays are dumb message buses: they accept events, store them for a while, and forward them to whoever subscribed. The `NostrPool` manages a set of them for you: it connects, reconnects dropped relays, and fans every request out to all of them.

## The pool

```java
NostrPool pool = new NostrPool();
```

There are two ways to add a relay. `ensureRelay` takes a URL and is idempotent: if a relay with that URL is already in the pool it just returns it (connected). `addRelay` takes an already-built `NostrRelay` instance, for when you need to configure it first.

```java
pool.ensureRelay("wss://relay.damus.io");            // add by URL, connect
pool.addRelay(new NostrRelay("wss://relay.ngengine.org")); // add instance, connect
```

Both connect the relay. A single relay on its own:

```java
NostrRelay relay = new NostrRelay("wss://relay.damus.io");
relay.connect();

relay.isConnected();                       // boolean
relay.disconnect("done");                  // no reconnect
relay.disconnect("paused", true);          // disconnect but keep auto-reconnect armed
relay.setAutoReconnect(true);              // default behavior when added via the pool
```

## Reconnects

You don't manage reconnects yourself. Every relay added through the pool gets two components attached automatically:

- a **watchdog** that reconnects the relay when the connection drops;
- a **lifecycle manager** that re-opens your subscriptions after a reconnect, so a dropped relay doesn't silently lose your live feeds. It also exposes a keep-alive (`setKeepAliveTime` / `keepAlive`) and `hasActiveSubscription(sub)` to inspect state.

If you want to observe the lifecycle, attach a component to the relay. `NostrRelayComponent` is the listener interface: `onRelayConnect`, `onRelayDisconnect` (tells you whether the client asked for it), `onRelayMessage`, `onRelayError`, `onRelaySend`, and a few `...Request` / `Before` / `After` hooks, all returning boolean.

```java
relay.addComponent(new NostrRelayComponent() {
    // implement only the callbacks you care about
});
```

## Publishing and acknowledgement tasks

`publish` sends the event to every connected relay and returns one `AsyncTask` per relay, so you can track each acknowledgement individually:

```java
List<AsyncTask<NostrMessageAck>> tasks = pool.publish(signed);

NostrMessageAck.Status status = NostrPoolQuorumAckPolicy.get().apply(tasks);
```

The three policy reducers, all singletons via `get()`, calculate an aggregate status from that list:

| Policy | Meaning |
|--------|---------|
| `NostrPoolAnyAckPolicy` | success as soon as **one** relay acks without failure |
| `NostrPoolAllAckPolicy` | success only if **every** relay acks; one failure fails the whole publish |
| `NostrPoolQuorumAckPolicy` | success on a **strict majority** of acks |

Call the selected reducer again as acknowledgement tasks settle: `PENDING` can later become `SUCCESS` or `FAILURE`.

> **0.3.1 behavior**
> The overload `publish(event, ackPolicy)` accepts a policy but does not apply it internally; it returns the same per-relay tasks as `publish(event)`. Apply the policy explicitly as above. This documentation names the behavior so callers do not accidentally treat the overload itself as an aggregate acknowledgement.

## Local event stores

An `EventStore` is a small local cache for events your app has seen: `addEvent` puts one in, `getEvents(filters)` reads back the ones matching a filter. The library ships two implementations:

- `InMemoryEventStore` - a plain in-memory map;
- `WeakInMemoryEventStore` - same, but entries are held with weak references, so they disappear under memory pressure instead of growing forever.

Use one when your app needs to answer "have I seen this event" without asking the network again.

## Event trackers and the sliding window

A different problem: the same event arrives from several relays at once, and your listener should see it once. That deduplication is the job of an **event tracker**, attached per subscription.

The default tracker for subscriptions created with `new NostrPool()` is the `ForwardSlidingWindowEventTracker`: it remembers event IDs it has seen inside a **sliding time window** (60 minutes by default, plus a 30-minute margin), keeping at least 21 and at most an unbounded number of entries. An event seen twice inside the window is reported once; old entries age out so the tracker doesn't grow forever.

The other trackers:

- `NaiveEventTracker` - remembers everything, no expiry; the default for `fetch`;
- `PassthroughEventTracker` - remembers nothing, every delivery passes through;
- `FailOnDoubleTracker` - throws if the same event arrives twice (for tests).

You can pick a tracker per subscription:

```java
pool.subscribe(filter, () -> new NaiveEventTracker());
```

Trackers also get `tuneFor(subscription)`, a hint called when the subscription opens so the tracker can size itself to the filters.

## Where next

- [Fetch & subscribe](fetch-subscribe.md) - fetch policies, defaults, EOSE
- [Concepts](concepts.md) - keys, events, signing, filters
