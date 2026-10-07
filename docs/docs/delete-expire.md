---
title: Delete and expire events
---

# Delete and expire events

Request deletion when you want to withdraw an event that has already been published. Set an expiration before signing when you know in advance when an event should stop being served.

## Request deletion

Sign a deletion request with the same account that signed the original event. The request is a kind-5 event listing the targets and an optional reason:

```java
UnsignedNostrEvent deletion =
    Nip09EventDeletion.createDeletionEvent("typo, reposting", signedNote);

signer.sign(deletion).compose(pool::publish);
```

You can target several events at once. For addressable (parameterized replaceable) events, there is a variant that takes event coordinates. Deletion requests follow NIP-09.

In your own app, check that a deletion was signed by the target event's author before removing it from your store and UI.

## Set an expiration

Add an expiration time before signing. This writes the NIP-40 `expiration` tag:

```java
UnsignedNostrEvent draft = new UnsignedNostrEvent()
    .withKind(1)
    .withContent("Live in one hour, ignore this afterwards")
    .withExpiration(Instant.now().plus(Duration.ofHours(2)));
```

To read the expiry on a received event:

```java
--8<-- "site-demos/src/backend/java/org/ngengine/site/demos/DocumentationExamples.java:event-expiration"
```

With the expiration tag set, relays that support NIP-40 stop serving the event after that time. Your app can also check the timestamp before displaying it.

`getExpiration()` always returns an `Instant`: a missing or empty expiration value gives a far-future time; a non-empty malformed or non-positive value gives `Instant.EPOCH`, so the event is expired. It does not throw for malformed expiration tags. `isCurrent()` also rejects a creation time more than 30 seconds in the future. These checks do not verify the signature.

!!! note "Copies may remain"
    Deletion and expiration depend on relays and clients honoring them. Neither can erase copies that have already been downloaded.
