---
title: NIP-24 Extra metadata
---

# NIP-24 - Extra metadata

NIP-24 extends the kind 0 profile with additional metadata fields. In Nostr4J, `Nip24ExtraMetadata` extends `Nip01UserMetadata`, so every getter/setter from [NIP-01](nip-01.md) remains available.

> **Compatibility note**
> `Nip24` is deprecated in 0.3.1 because this functionality moved into `Nip01`. Existing code still works, but new code should prefer `Nip01.fetch` / `Nip01.update` and use `Nip24ExtraMetadata` only when it needs the extra fields.

```java
Nip24ExtraMetadata meta = Nip24.fetch(pool, pubkey).get();

// ... read or change fields, NIP-01 style ...

List<AsyncTask<NostrMessageAck>> acks =
    Nip24.update(pool, signer, meta).get();
```

You can also convert between the two representations:

```java
Nip24ExtraMetadata fromMetadata = Nip24.from(nip01metadata);
Nip24ExtraMetadata fromEvent = Nip24.from(sourceEvent);
```

## Where next

- [NIP-01](nip-01.md) - base profile metadata
- [NIP-39](nip-39.md) - external identities
- [NIPs index](nips.md)
