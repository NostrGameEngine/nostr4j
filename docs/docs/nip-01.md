---
title: Read and update a profile
---

# Read and update a profile

To show someone's name, picture or bio, fetch their kind-0 profile event. You get a `Nip01UserMetadata` object with accessors for those fields:

## Reading a profile

```java
Nip01UserMetadata meta = Nip01.fetch(pool, pubkey).await();

meta.getName();         // "alice"
meta.getDisplayName();  // "Alice"
meta.getAbout();        // bio text
meta.getPicture();      // URL
meta.getWebsite();      // URL
```

You get the newest profile event found for that public key. Use an overload with a timeout or a `Nip01UserMetadataFilter` when you need more control over the read.

The picture field is a URL. Verifying or reading the profile does not load the image; check the URL and choose when your application should request it.

## Updating your profile

Fetch your current metadata (or build a fresh `Nip01UserMetadata`), change the fields, and publish:

```java
Nip01UserMetadata meta = Nip01.fetch(pool, myPubkey).await();
meta.setDisplayName("Alice");
meta.setAbout("Building things with Nostr4J");
meta.setPicture("https://example.com/alice.png");

List<AsyncTask<NostrMessageAck>> acks =
    Nip01.update(pool, signer, meta).await(); // published with the default ack policy
```

After the update completes, at least one relay has accepted the signed profile event. Use the returned tasks to inspect the remaining relay acknowledgements. To work with the events directly, use `meta.toUpdateEvent()` for the edited profile or `meta.getSourceEvent()` for the original.

Other clients see the change once their relays have the new profile. An acknowledgement from your relay cannot confirm that every copy has been updated.

Profiles can also include [extra fields](nip-24.md) and [links to external identities](nip-39.md).
