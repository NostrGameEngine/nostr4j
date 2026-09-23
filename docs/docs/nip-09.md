---
title: NIP-09 Deletion
---

# NIP-09 - Deletion requests

Nostr events can't be un-sent, but you can ask relays to delete them. A kind 5 event lists the events to remove, with an optional human-readable reason. `Nip09EventDeletion` builds it:

```java
UnsignedNostrEvent deletion =
    Nip09EventDeletion.createDeletionEvent("typo, reposting", signedNote);

signer.sign(deletion).then(signed -> {
    pool.publish(signed);
    return null;
});
```

You can target several events at once, and there is a variant that takes event coordinates for addressable (parameterized replaceable) events.

Two honest caveats:

- Relays **may** honor the request; nothing forces them. Assume the event is still out there.
- Your own app should honor deletions it sees: when you receive a kind 5 from the author of the target events, drop them from your local store and UI.

## Where next

- [NIPs index](nips.md)
