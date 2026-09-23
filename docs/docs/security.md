---
title: Security
description: Handle keys, event content, wallet connections and room access safely.
---

# Security

A signed event can still contain malicious HTML or an unsafe URL. Keep signature checks enabled, but also consider what happens when your app stores, displays or follows the data it receives.

## Generate a secure key

After initializing your [platform adapter](platforms.md), use Nostr4j's key generators. They use the platform's own CSPRNG.

To generate a private key and derive its public key:

```java
NostrPrivateKey privateKey = NostrPrivateKey.generate();
NostrPublicKey publicKey = privateKey.getPublicKey();
```

If you need both as a key pair, use the no-argument constructor for a fresh private key from the same secure generator:

```java
NostrKeyPair keys = new NostrKeyPair();
```

To create a local signer with a new identity in one step:

```java
NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
```

Call the generator again when you need another random identity. For an account that must survive restarts, save the key with [encrypted key storage](keys.md#store-a-private-key-with-a-passphrase) and load it on subsequent launches.

!!! tip "Let the library generate the key"
    These generators are secure and hardened by default

## Keys and credentials

Keep private keys behind a signer where possible. In a browser, route signing through an extension so your page never sees the key. On other platforms, use a remote signer the same way. Request only the permissions your app needs.

For a local key, use [encrypted storage](keys.md) and close the private key or key pair when you're finished with it. Closing a `NostrKeyPairSigner` does not destroy its key pair. Closing a key cannot erase copies you've made as Java strings. Avoid those copies where possible, and keep private keys, passphrases, NWC URIs and bunker secrets out of logs.

## Event content and URLs

Render event content as text, such as with `textContent` in a browser. If your app supports rich content, sanitize it before inserting HTML. Check URLs before loading profile images, media or links; even an ordinary remote image can reveal a reader's IP address to its host.

You can check incoming events against your filters locally with `subscription.setVerifyMatchLocally(true)`. Also set limits on how many events you retain, how long requests can wait and how much data a cache can hold.

Use `wss://` for relays and `https://` for media and LNURL endpoints. Loopback destinations are blocked by default. If you enable them for local development, keep that setting limited to the development process and avoid accepting arbitrary destination URLs from users.

## Wallet connections

Anyone holding an NWC URI can use the permissions granted to it. Ask for a connection with only the operations your app needs, store it as a credential and let the user revoke it when they disconnect.

To validate a zap receipt, call `Nip57.parseAndValidateZapReceipt(...)` against the invoice and request you created. A kind-9735 event that parses successfully does not prove the expected payment happened.

## RTC Room access

Create a dedicated random key pair for the room:

```java
NostrKeyPair roomKeyPair = new NostrKeyPair();
```

Generate it once when creating the room, then distribute the shared room key through an authenticated, encrypted channel to the members allowed to join. Each participant uses that same room key pair and a separate identity signer. Keep the room key separate from personal account keys.

A guessable room seed allows others to derive the key and join; use the random key above for private rooms.

When hosting TURN, keep the endpoint behind TLS and retain the server's authentication, frame and queue limits. Follow the [deployment instructions](turn-server.md) to set up the container and reverse proxy.

Use temporary identities and limited wallet connections when trying demos. You don't need to enter a real private key to use the examples on this site.
