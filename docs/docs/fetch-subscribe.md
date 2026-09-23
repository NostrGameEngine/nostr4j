---
title: Fetch and subscribe
---

# Fetch & subscribe

<a class="doc-run-button" href="../demos.html#relay">Run relay fetch demo</a>

Use `fetch` when you need a result set, such as a profile or a page of notes. For a feed that should keep updating, use `subscribe`: matching history arrives first, then new events as they happen.

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
    20, Duration.ofSeconds(10)).await();
```

## Subscriptions

Create the subscription, attach your listeners, then call `open()`.


| Listener | Callback | When it fires |
|----------|----------|---------------|
| `addEventListener` | `onSubEvent(sub, event, stored)` | for every matching event |
| `addEoseListener` | `onSubEose(sub, relay, everyWhere)` | when a relay finishes sending history |
| `addOpenListener` | `onSubOpen(sub)` | when the subscription opens |
| `addCloseListener` | `onSubClose(sub, reasons)` | when it closes |

When handling incoming events, distinguish history from new activity:

- Use **`stored`** to tell whether the event is history (`true`) or arrived live (`false`). A freshly opened subscription first replays stored events, then flips to live.
- **`EOSE`** ("end of stored events") means a relay has finished sending its history. After EOSE, only new events arrive. `onSubEose` is invoked once per relay; `everyWhere` is true when every relay in the pool has sent EOSE.

!!! tip
    Call `setVerifyMatchLocally(true)` to also check received events against the filter locally. This is useful when a relay is malicious, malfunctioning, or limited in its capabilities.


!!! tip
    Remember to `close()` the subscription when you no longer need it.

## Fetch policies

A fetch needs a stopping condition. Usually that is a number of events or a timeout, but you can also wait until the relays have finished sending their stored results.

| Policy | Stops when… |
|---|---|
| `NostrWaitForEventFetchPolicy` | The requested number of matching events arrives, the timeout expires, or all relays send EOSE if `endOnEose` is enabled |
| `NostrAllEOSEPoolFetchPolicy` | Every relay sends EOSE; a silent relay can keep the fetch waiting |

The default `fetch(filter, numEvents, timeout)` is shorthand for:

```java
pool.fetch(filter, numEvents, /* withEose = */ false, timeout);
```

With this default, EOSE alone does not end the fetch: it waits for `numEvents` results or the timeout. Pass `withEose = true` to return earlier when all relays have finished sending history. The returned list is ordered newest-first.

To use a policy explicitly:

```java
// finish after every relay has sent its stored results
AsyncTask<List<SignedNostrEvent>> task = pool.fetch(
    List.of(new NostrFilter().withKind(1).limit(100)),
    NostrAllEOSEPoolFetchPolicy.get());

// wait for 5 matching events, max 30 seconds, stop early on EOSE
NostrPoolFetchPolicy policy = NostrWaitForEventFetchPolicy.get(
    ev -> ev.getContent().contains("Nostr4j"),
    5, true, Duration.ofSeconds(30));
```
  
