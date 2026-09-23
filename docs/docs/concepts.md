---
title: Concepts
---

# Concepts

Four ideas carry the whole library: **keys** identify people, **events** are the only data format, **signers** turn events into signed messages, and **filters** describe the events you want to read. Relays, publish, fetch and subscribe have their own pages.

## Keys

A Nostr identity is a key pair. The public key is your identity; the private key is your password. Never share the private key, never log it, never send it anywhere.

Nostr4J represents keys as `NostrPublicKey` and `NostrPrivateKey`. The same key comes in several text formats:

```java
// public key: hex or npub
NostrPublicKey fromHex = NostrPublicKey.fromHex("deadbeef...");
NostrPublicKey fromNpub = NostrPublicKey.fromNpub("npub1...");

String hex  = fromHex.asHex();
String npub = fromHex.asBech32();      // npub1...

// private key: hex or nsec
NostrPrivateKey privateFromHex = NostrPrivateKey.fromHex("...");
NostrPrivateKey privateFromNsec = NostrPrivateKey.fromNsec("nsec1...");

String nsec = privateFromHex.asBech32(); // nsec1...

// fresh random key pair
NostrPrivateKey fresh = NostrPrivateKey.generate();
NostrKeyPair pair = new NostrKeyPair(fresh);
NostrPublicKey me = pair.getPublicKey();
```

There is one more private key format: `ncryptsec1...` is an **encrypted** private key (NIP-49). It needs a passphrase to decrypt, which makes it the right format to store on disk or in a database. It is covered on the [Keys & signers](signers.md) page.

## Events

An event is a small signed JSON object: a **kind** (a number saying what the event is), a **content** string, a list of **tags**, and a timestamp. Everything on Nostr is an event. Some common kinds:

| Kind | Meaning |
|------|---------|
| 0 | profile metadata (name, picture, about) |
| 1 | text note |
| 3 | contact list |
| 4 | encrypted direct message (legacy) |
| 5 | deletion request |
| 44 | encrypted direct message (modern) |
| 9734 / 9735 | zap request / zap receipt |

You build an event unsigned, add whatever it needs, then sign it. Tags are key/value lists, for example `["t", "introduction"]` for a hashtag or `["p", "<hex>"]` to mention someone:

```java
UnsignedNostrEvent note = new UnsignedNostrEvent()
    .withKind(1)
    .withContent("Hello, Nostr")
    .withTag("t", "introduction")
    .withTag("p", otherPubkey.asHex());
```

The signed result is a `SignedNostrEvent`: immutable, ready to publish, and also what you receive back from relays.

## Signing

Apps never sign with a raw private key. They depend on the `NostrSigner` interface, which hides where the key actually lives: in memory, in a browser extension, or on a remote bunker. **Depend on `NostrSigner`, not on keys.**

```java
NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair());

signer.sign(note).then(signed -> {
    // signed is a SignedNostrEvent, ready to publish
    return null;
});
```

The same interface also encrypts and decrypts direct messages, and each implementation (local key pair, browser extension via NIP-07, remote signing via NIP-46) exposes the same methods. Details on the [Keys & signers](signers.md) page.

## Filters

A filter describes the events you are interested in: kind, author, time range, tags, limit.

```java
NostrFilter recentNotes = new NostrFilter()
    .withKind(1)
    .withAuthor(somePubkey)
    .since(Instant.now().minus(Duration.ofDays(7)))
    .limit(50);
```

Filters are what you pass to `pool.subscribe()` and `pool.fetch()`. What those two do, and how to control them, is on the [fetch & subscribe](fetch-subscribe.md) page. How events reach relays is on the [relays](relays.md) page.

## Where next

- [Relays & connections](relays.md) - pools, reconnects, ack policies, local stores
- [Fetch & subscribe](fetch-subscribe.md) - fetch policies, defaults, EOSE
- [Keys & signers](signers.md) - NIP-07, NIP-46, encrypted keys and messages
