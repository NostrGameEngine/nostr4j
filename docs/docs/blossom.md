---
title: Blossom media storage
---

# Blossom - media storage

Upload an image or another file to a Blossom server, then put its URL in your Nostr event. Files are addressed by their SHA-256 hash. Sign uploads and deletions with your signer. Start by adding an endpoint to a `BlossomPool`:

```java
BlossomPool blossom = new BlossomPool(signer);   // signer authenticates uploads
blossom.ensureEndpoint(new BlossomEndpoint("https://blossom.example.com"));

// upload: the SHA-256 is computed and signed into the auth header for you
BlobDescriptor blob = blossom.upload(ByteBuffer.wrap(imageBytes)).await();
blob.getUrl();      // where to fetch it
blob.getSha256();   // the content hash
blob.getSize();

// download (no auth needed) and existence check
ByteBuffer back = blossom.get(blob.getSha256()).await();
boolean there = blossom.exists(blob.getSha256()).await();

// list a user's uploads, delete your own
List<BlobDescriptor> mine = blossom.list(myPubkey).await();
blossom.delete(blob.getSha256()).await();
```

You can supply a file name and MIME type when uploading. Limit a listing to a time range. Close the pool when you no longer need it.

Blob verification is enabled by default on `BlossomEndpoint`. An upload checks that the returned descriptor's SHA-256 and size match the submitted bytes; a full download checks its bytes against the requested hash. A mismatch fails the task. Keep verification enabled and validate the returned URL before placing it in your UI or following it.

The download overload accepts a byte range, but the default verifier hashes the returned body against the full blob hash. A partial response therefore fails that check. Range downloads require turning off verification with `setVerifyBlobs(false)` on the endpoint and performing the integrity checks appropriate to your application's partial-data format.

Use `blob.getUrl()` in the event's content or media tags. The hash identifies the uploaded bytes; the URL is still supplied by the server.
