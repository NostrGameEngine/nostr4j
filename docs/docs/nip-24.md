---
title: Use extended profile fields
---

# Use extended profile fields

Extra profile fields share the same kind-0 event as the name and bio. You can access them through `Nip24ExtraMetadata`, which inherits the usual [profile metadata](nip-01.md) methods.

For new code, use `Nip01.fetch` and `Nip01.update`. The older `Nip24` helper is deprecated; you may still encounter it in code like this:

```java
Nip24ExtraMetadata meta = Nip24.fetch(pool, pubkey).await();

// ... read or change fields, NIP-01 style ...

List<AsyncTask<NostrMessageAck>> acks =
    Nip24.update(pool, signer, meta).await();
```

You can also convert between the two representations:

```java
Nip24ExtraMetadata fromMetadata = Nip24.from(nip01metadata);
Nip24ExtraMetadata fromEvent = Nip24.from(sourceEvent);
```

To associate the profile with accounts on other services, see [external identities](nip-39.md).
