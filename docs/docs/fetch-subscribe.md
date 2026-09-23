---
title: Fetch and subscribe
---

# Fetch & subscribe

Two ways to read events. `subscribe` opens a **live subscription**: you get past events first, then every new one until you close it. `fetch` is a **one-shot read** on top of a temporary subscription: it collects events, closes itself, and hands you a list ordered newest-first.

```java
// live: keeps delivering until you call sub.close()
NostrSubscription sub = pool.subscribe(
    new NostrFilter().withKind(1).limit(20));
sub.addEventListener((s, event, stored) -> {
    System.out.println((stored ? "[history] " : "[live] ") + event.getContent());
});
sub.open();

// one-shot: returns the collected events
List<SignedNostrEvent> notes = pool.fetch(
    new NostrFilter().withKind(1).limit(20),
    20, Duration.ofSeconds(10)).get();
```

## Subscriptions

A subscription goes through `open()` and `close()` (both return the per-relay ack tasks, like publish). `isOpened()` tells you the current state. Listeners are added fluently and can be removed with `removeListener`:

| Listener | Callback | When it fires |
|----------|----------|---------------|
| `addEventListener` | `onSubEvent(sub, event, stored)` | for every matching event |
| `addEoseListener` | `onSubEose(sub, relay, everyWhere)` | when a relay finishes sending history |
| `addOpenListener` | `onSubOpen(sub)` | when the subscription opens |
| `addCloseListener` | `onSubClose(sub, reasons)` | when it closes |

Two things in the event callback deserve attention:

- **`stored`** tells you whether the event is history (`true`) or arrived live (`false`). A freshly opened subscription first replays stored events, then flips to live.
- **`EOSE`** ("end of stored events") is the relay saying "that's all I have for this filter". After EOSE, only new events arrive. `onSubEose` fires per relay; `everyWhere` is true when every relay in the pool has sent it.

`setVerifyMatchLocally(true)` makes the subscription double-check each incoming event against the filter on your side, in case a relay is sloppy.

## Fetch policies

A **fetch policy** decides when a `fetch` is done. It is a listener: the pool calls `getListener(subscription, collectedEvents, end)` and the policy calls `end` when its condition is met, which closes the temporary subscription. There are two built in, both singletons via `get()`:

- **`NostrAllEOSEPoolFetchPolicy`** - finishes when **every** relay has sent EOSE. Use it when you want everything the relays have, no matter how long that takes.
- **`NostrWaitForEventFetchPolicy`** - finishes when **N matching events** arrive, or when every relay sent EOSE (if `endOnEose` is true), or when the **timeout** fires. This is the default.

The default `fetch(filter, numEvents, timeout)` is shorthand for:

```java
pool.fetch(filter, numEvents, false, timeout);
```

which uses a `NostrWaitForEventFetchPolicy` that waits for `numEvents` events matching everything (`e -> true`) and gives up at `timeout`. In plain words: **the default waits for N events or the timeout; it does not stop at EOSE.** Pass `withEose = true` when the result should return as soon as all relays say their stored history is exhausted.

To use a policy explicitly:

```java
// wait for everything the relays have
AsyncTask<List<SignedNostrEvent>> task = pool.fetch(
    List.of(new NostrFilter().withKind(1).limit(100)),
    NostrAllEOSEPoolFetchPolicy.get());

// wait for 5 events mentioning us, max 30 seconds, stop early on EOSE
NostrPoolFetchPolicy policy = NostrWaitForEventFetchPolicy.get(
    ev -> ev.getContent().contains("nostr4j"),
    5, true, Duration.ofSeconds(30));
```

A fetch policy is just a listener, so you can attach one to a **manual** subscription too and close it yourself in `end`:

```java
NostrSubscription sub = pool.subscribe(filter);
List<SignedNostrEvent> collected = new ArrayList<>();
sub.addListener(NostrAllEOSEPoolFetchPolicy.get()
    .getListener(sub, collected, () -> sub.close()));
sub.open();
```

`NostrSubAllListener` is the interface that combines all four subscription callbacks; it's what fetch policies are built on.

## Choosing between them

- Reading a profile, a contact list, or "the latest N notes": `fetch`.
- A chat, a live feed, anything that updates: `subscribe`, and `close()` it when the screen goes away.
- Fetch with the default policy when you want "up to N, quickly"; fetch with `NostrAllEOSEPoolFetchPolicy` when completeness matters more than speed.

## Where next

- [Relays & connections](relays.md) - pools, reconnects, ack policies, local stores
- [Concepts](concepts.md) - keys, events, signing, filters
