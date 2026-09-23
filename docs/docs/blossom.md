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

You can supply a file name and MIME type when uploading. Request a byte range when downloading. Limit a listing to a time range. Close the pool when you no longer need it.

Use `blob.getUrl()` in the event's content or media tags. The hash identifies the uploaded bytes. Compare it with the downloaded content to detect a file that has changed.
