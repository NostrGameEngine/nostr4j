---
title: Link an external identity
---

# Link an external identity

You can add accounts from other services to a Nostr profile, along with proof that you control them. These NIP-39 links are stored in the kind-0 profile event. For example, read an existing GitHub link like this:

```java
Nip39ExternalIdentities ids = Nip39.fetch(pool, pubkey).await();

ids.getExternalIdentities();                    // all linked identities
ids.getExternalIdentity("github");              // the GitHub link, if any
```

To change a link, edit the profile and publish it:

```java
ids.setExternalIdentity("github", "alice",
    List.of("https://gist.github.com/alice/proof"));
ids.removeExternalIdentity("twitter");

// Nip39.update currently accepts Nip24ExtraMetadata, not the more specific
// Nip39ExternalIdentities type. Nip01.update accepts this subclass directly.
List<AsyncTask<NostrMessageAck>> acks =
    Nip01.update(pool, signer, ids).await();
```

Check supported platform names with `Nip39.isValidPlatform("github")`. A link in a profile is still a claim by its author; verify the accompanying proof before showing it as a confirmed identity. Relays do not perform that check.
