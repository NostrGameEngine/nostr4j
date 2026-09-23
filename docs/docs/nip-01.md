---
title: NIP-01 Metadata
---

# NIP-01 - Profile metadata

Kind 0 events carry a profile: name, display name, picture, about, website, and the NIP-05 identifier. `Nip01` reads and writes them; `Nip01UserMetadata` is the object you work with.

## Reading a profile

```java
Nip01UserMetadata meta = Nip01.fetch(pool, pubkey).get();

meta.getName();         // "alice"
meta.getDisplayName();  // "Alice"
meta.getAbout();        // bio text
meta.getPicture();      // URL
meta.getWebsite();      // URL
```

`fetch` takes the newest kind 0 it finds for that pubkey. There are overloads with a timeout and with a `Nip01UserMetadataFilter` if you need one.

## Updating your profile

Fetch your current metadata (or build a fresh `Nip01UserMetadata`), change the fields, and publish:

```java
Nip01UserMetadata meta = Nip01.fetch(pool, myPubkey).get();
meta.setDisplayName("Alice");
meta.setAbout("Building things with Nostr4J");
meta.setPicture("https://example.com/alice.png");

List<AsyncTask<NostrMessageAck>> acks =
    Nip01.update(pool, signer, meta).get(); // sign, publish, then expose per-relay acks
```

`update` is asynchronous twice: its outer task covers signing, and its result is the list of per-relay acknowledgement tasks returned by `pool.publish`. The underlying event is available as `meta.toUpdateEvent()`, and `meta.getSourceEvent()` gives you the kind 0 it was read from.

> **Tip**
> Profiles are per-relay and eventually consistent: after `update`, other clients see the new profile once the relays they read from have the new kind 0. There is no global "save" confirmation beyond the relay acks.

## Where next

- [NIP-24](nip-24.md) - extra metadata fields on top of kind 0
- [NIP-39](nip-39.md) - external identities (GitHub, Twitter, …)
- [NIPs index](nips.md)
