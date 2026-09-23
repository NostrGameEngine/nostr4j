---
title: Blossom media storage
---

# Blossom - media storage

Blossom is content-addressed media storage with Nostr authentication: you upload bytes, you get back a SHA-256 hash and a URL. Nostr4J implements it in `org.ngengine.blossom4j` around `BlossomPool`.

```java
BlossomPool blossom = new BlossomPool(signer);   // signer authenticates uploads
blossom.ensureEndpoint(new BlossomEndpoint("https://blossom.example.com"));

// upload: the SHA-256 is computed and signed into the auth header for you
BlobDescriptor blob = blossom.upload(ByteBuffer.wrap(imageBytes)).get();
blob.getUrl();      // where to fetch it
blob.getSha256();   // the content hash
blob.getSize();

// download (no auth needed) and existence check
ByteBuffer back = blossom.get(blob.getSha256()).get();
boolean there = blossom.exists(blob.getSha256()).get();

// list a user's uploads, delete your own
List<BlobDescriptor> mine = blossom.list(myPubkey).get();
blossom.delete(blob.getSha256()).get();
```

`upload` has overloads for a file name and MIME type; `get` supports byte ranges for partial downloads; `list` takes an optional time range. `close()` shuts the pool down.

The typical Nostr flow: upload the image to Blossom, then put `blob.getUrl()` in your event's content or tags. The hash in the URL is the integrity check - if the bytes don't match the hash, they aren't the file you linked.

## Where next

- [NIPs index](nips.md)
- [Keys & signers](signers.md) - the signer used for Blossom auth
