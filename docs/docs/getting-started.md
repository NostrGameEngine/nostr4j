---
title: Getting started
---

# Getting started

Nostr4J is a Nostr library for the JVM. It is built for speed and low memory use, and the same code compiles to JavaScript: the live demos on this site are the library itself running in your browser.

The library gives you a small set of building blocks: keys, events, relay pools, signers, wallets, RTC rooms. It does not push an architecture on you. You wire the pieces together the way your app needs.

## Requirements

The `nostr4j` library targets Java 11. The JVM networking platform and the repository test suite require Java 21 or newer. The examples below use Gradle and the JVM platform.

## Add the dependency

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.ngengine:nostr4j:0.3.1'
}
```

Check the [releases page](https://github.com/NostrGameEngine/nostr4j/releases) for the latest version number.

On desktop you also need the platform artifact, which provides the actual networking and crypto:

```gradle
dependencies {
    implementation 'org.ngengine:nge-platform-jvm:0.2.4'
}
```

<div class="callout note" markdown="1">
<span class="callout-label">Note</span>
The platform layer blocks `localhost` addresses by default. If your app talks to local services on purpose, start the JVM with `-Dnge-platforms.allowLoopbackInURIs=true`.
</div>

## Publish your first event

You need three things: a pool, a keypair, and an event.

```java
import org.ngengine.nostr4j.*;
import org.ngengine.nostr4j.event.*;
import org.ngengine.nostr4j.keypair.*;
import org.ngengine.nostr4j.signer.*;

NostrPool pool = new NostrPool();
pool.ensureRelay("wss://relay.damus.io").get();

NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair());

UnsignedNostrEvent note = new UnsignedNostrEvent()
        .withKind(1)
        .withContent("Hello from Nostr4J");

signer.sign(note).then(signed -> {
    pool.publish(signed);
    return null;
});
```

What is happening here:

* `NostrPool` holds your relay connections. You add relays; the pool connects, reconnects when the network drops, and spreads your reads and writes across them.
* `NostrKeyPairSigner` signs with a fresh throwaway keypair. In a real app you load the user's key instead (see Concepts).
* `sign()` is asynchronous. Almost everything in Nostr4J returns an `AsyncTask`, and you chain the next step with `.then()` instead of blocking.
* `publish()` sends the signed event to every relay in the pool and returns one acknowledgement task per relay. Inspect those tasks when delivery confirmation matters.

## Read events back

Subscriptions work the same way. Build a filter, subscribe, listen:

```java
NostrSubscription sub = pool.subscribe(
        new NostrFilter().withKind(1).limit(10));

sub.addEventListener((s, event, stored) -> {
    System.out.println(event.getContent());
});

sub.open();
```

The filter says what you want: kind 1 (text notes), at most 10 of them. `addEventListener` fires once per event, and `open()` starts the subscription. There are also listeners for the end of the stored batch (`addEoseListener`) and for the subscription closing (`addCloseListener`).

Close resources when their owner stops:

```java
sub.close();
signer.close().get();
pool.close();
```

<div class="callout tip" markdown="1">
<span class="callout-label">Tip</span>
`NostrFilter` has a fluent API: `.withAuthor(...)`, `.withKind(...)`, `.since(...)`, `.until(...)`, `.limit(...)`, `.withTag("e", ...)`. Combine them freely; the pool translates them into the right relay messages.
</div>

## Where to go from here

* [Concepts](concepts.md) explains keys, events and relays in five minutes, including how to load a real user key instead of a throwaway.
* [NIPs](nips.md) goes through every supported NIP with a short example each.
* [Wallets](wallets.md) shows how to connect a Lightning wallet over NWC.
* [RTC rooms](rtc-rooms.md) is the realtime part: peer-to-peer data over WebRTC with Nostr as signaling.

Use the [Javadoc](../api/index.html) for exact signatures and the [source on GitHub](https://github.com/NostrGameEngine/nostr4j) for implementation details. Snapshot builds can move ahead of the latest stable release.
