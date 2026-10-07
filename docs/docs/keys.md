---
title: Keys
---

# Keys

<a class="doc-run-button" href="../demos.html#publish">Run key and signing demo</a>

A Nostr account is identified by a public key. Whoever holds the matching private key can sign for that account, so your app's first decision is where to keep that key: locally, in a browser extension, or with a remote signer.

For a local account, load an existing key from hex or `nsec` text. Public keys have their own hex and `npub` forms:

```java
NostrPrivateKey secret = NostrPrivateKey.fromBech32(userSuppliedNsec);
NostrKeyPair keys = new NostrKeyPair(secret);
NostrPublicKey identity = keys.getPublicKey();
String npub = identity.asBech32();
```

To create a new account, use `new NostrKeyPair()`. Keep that identity between launches if the user expects to return to the same account. Call `keys.close()` when you're finished with a locally owned key, and keep private key text out of logs.

## Store a private key with a passphrase

Before saving a key, encrypt it as an `ncryptsec` string. This is the NIP-49 format; recovering the key requires the passphrase:

```java
String encrypted = secret.asNcryptsec(passphrase).await();
NostrPrivateKey restored = NostrPrivateKey.fromNcryptsec(encrypted, passphrase).await();
restored.close();
```

Key derivation takes time by design, which is why these calls are asynchronous. Save the encrypted string in protected application storage and ask the user for their passphrase when needed.

If you use a [browser or remote signer](signers.md), the private key can stay outside your application entirely.
