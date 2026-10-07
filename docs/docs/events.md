---
title: Events
---

# Events

An event is the basic unit of data in Nostr. Notes, profiles, reactions and application messages share the same structure; their `kind` determines how to interpret the content and tags. Each signed event carries an author, an identifier and a signature. You can verify it yourself, no matter where it came from.

## Event structure

| Field | Meaning |
|---|---|
| `pubkey` | The author's public key |
| `created_at` | Creation time, expressed as Unix seconds |
| `kind` | An integer identifying the event type |
| `tags` | Lists of strings containing references and other metadata |
| `content` | A string whose format depends on the kind |
| `id` | SHA-256 hash of the serialized event data |
| `sig` | Schnorr signature of the event ID, made with the author's private key |

For example, kind 1 uses plain text for a short note, while kind 0 uses a JSON string for profile metadata. The relevant protocol defines which tags and content an event needs.

The ID is computed from the public key, creation time, kind, tags and content using Nostr's canonical serialization. Change any of those fields and the ID changes too - the event needs signing again.

## Compose an event

Start with an `UnsignedNostrEvent` and fill in the data you want to sign:

```java
UnsignedNostrEvent draft = new UnsignedNostrEvent()
    .withKind(1)
    .createdAt(Instant.now())
    .withContent("Hello, Nostr")
    .withTag("t", "introductions")
    .withTag("t", "java");
```

The creation time defaults to construction time (an `Instant`); set it explicitly when you need a different timestamp.

Each `withTag` call adds a tag row. The example adds `["t", "introductions"]` and `["t", "java"]`: multiple tags can have the same name, and a tag can contain several values. Common names include `t` for hashtags, `e` for event references and `p` for public-key references. Their exact meaning depends on the event kind and protocol.

Use `replaceTag` to replace all rows with a given name, or `clearTags` to remove them. Finish editing the draft before you sign it.

## Sign an event

Pass the draft to a `NostrSigner`. For a local key pair:

```java
NostrKeyPairSigner signer = new NostrKeyPairSigner(keys);
SignedNostrEvent event = signer.sign(draft).await();

System.out.println(event.getId());
System.out.println(event.getPubkey().asHex());
```

Here, `keys` is the author's `NostrKeyPair`. Calling `sign()` turns the draft into a separate `SignedNostrEvent` with the ID and signature filled in; the draft itself is left unchanged. Editing the draft afterwards does not change the signed event.

The return value is an `AsyncTask<SignedNostrEvent>`; the example uses `.await()` to run the steps in sequence. You can also compose the task with the next operation. The same signing interface works with browser extensions and remote signers; see [signers](signers.md) to choose how your app accesses an identity.

## Verify an event

A `SignedNostrEvent` contains a signature, but that alone does not establish its validity. Call `verify()` to recompute the ID and check the signature against the author's public key:

```java
boolean valid = event.verify();
if (!valid) {
    throw new IllegalArgumentException("Invalid event ID or signature");
}
```

For asynchronous verification, use `verifyAsync()`, which returns an `AsyncTask<Boolean>`:

```java
event.verifyAsync().then(valid -> {
    if (valid) {
        System.out.println("Verified event: " + event.getId());
    }
    return null;
});
```

An ID or signature mismatch gives you `false`. If the input cannot be processed, the call throws an exception or the asynchronous task fails. Handle that case when you accept external data.

!!! note
    A valid signature proves that the event data matches what was signed with the corresponding private key. It does not establish that the content is true or that it follows the rules of its kind. Check those rules separately before using the event in your application.

## Read and write event JSON

Use `toEventJSON()` for a signed event's JSON object and `SignedNostrEvent.fromJSON()` to read it back:

```java
String json = event.toEventJSON();
SignedNostrEvent parsed = SignedNostrEvent.fromJSON(json);
if (!parsed.verify()) {
    throw new IllegalArgumentException("Invalid event ID or signature");
}
```

This JSON contains the event fields, not a relay's `EVENT` message envelope. Parsing does not authenticate the event: verify it before accepting external data, and handle malformed input separately.

Signed events own their tag rows and expose read-only views. Repeated tag lookups and event encodings reuse cached results; on immutable event models, the serialized JSON remains in memory for the lifetime of the event. Use an unsigned draft when you need to edit an event, then sign it again.
