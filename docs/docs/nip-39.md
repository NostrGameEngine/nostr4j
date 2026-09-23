---
title: NIP-39 External identities
---

# NIP-39 - External identities

NIP-39 links your Nostr profile to identities elsewhere: GitHub, Twitter, Telegram, and so on. Each link lives in the kind 0 profile with an optional proof (usually a URL showing you control that account).

```java
Nip39ExternalIdentities ids = Nip39.fetch(pool, pubkey).get();

ids.getExternalIdentities();                    // all linked identities
ids.getExternalIdentity("github");              // the GitHub link, if any
```

Adding or removing a link, then publishing:

```java
ids.setExternalIdentity("github", "alice",
    List.of("https://gist.github.com/alice/proof"));
ids.removeExternalIdentity("twitter");

// Nip39.update currently accepts Nip24ExtraMetadata, not the more specific
// Nip39ExternalIdentities type. Nip01.update accepts this subclass directly.
List<AsyncTask<NostrMessageAck>> acks =
    Nip01.update(pool, signer, ids).get();
```

`Nip39.isValidPlatform("github")` checks a platform name before you use it. Like everything in kind 0, this is self-attested: the proof URL is a convention between the user and whoever verifies it, not something relays check. The `Nip39.update` signature in 0.3.1 is broader-but-inconvenient (`Nip24ExtraMetadata`), so the example deliberately calls the underlying `Nip01.update` API.

## Where next

- [NIP-01](nip-01.md) - base profile metadata
- [NIPs index](nips.md)
