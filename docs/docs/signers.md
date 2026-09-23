---
title: Keys and signers
---

# Keys & signers

[Concepts](concepts.md) showed the key formats. This page is about what you *do* with keys: sign events, encrypt messages, and the three ways a key can live (in your app, in the browser, on a remote bunker).

## The signer abstraction

Everything signs through the `NostrSigner` interface. The point is simple: your code calls `sign()`, and it doesn't care where the private key is.

```java
NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair());

signer.sign(unsignedEvent).then(signed -> {
    pool.publish(signed);
    return null;
});
```

The interface, in full:

- `sign(UnsignedNostrEvent)` → the signed event, the method you use 99% of the time;
- `getPublicKey()` → the public key this signer acts for;
- `encrypt(message, theirPublicKey)` / `decrypt(message, theirPublicKey)` → encrypted direct messages, NIP-44 by default (pass `EncryptAlgo.NIP04` for the legacy format);
- `powSign(event, difficulty)` → proof-of-work signing (NIP-13), for relays that demand it;
- `isAvailable()` → mostly for remote signers: is the other side reachable;
- `close()` → release the signer.

There are three implementations. Same methods, different places the key lives.

## Local signer

`NostrKeyPairSigner` holds the key pair in memory and signs locally. This is what servers, bots and tests use.

```java
NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
```

If the key comes from the user, parse it first (`NostrPrivateKey.fromNsec(...)`) and wrap it in a `NostrKeyPair`. Never hold a raw `nsec` longer than you have to.

## Browser signer (NIP-07)

`NostrNIP07Signer` talks to a browser extension (Alby, nos2x, …). The key never leaves the extension: your app sends the unsigned event, the extension shows its own prompt, and returns the signature.

```java
NostrSigner signer = new NostrNIP07Signer();   // no arguments: it finds window.nostr
```

This is the right choice for web apps: the user keeps their key, you never see it. `isAvailable()` tells you whether an extension is actually installed.

## Remote signer (NIP-46)

`NostrNIP46Signer` signs through a remote **bunker** over Nostr. The app holds a throwaway client key pair; the real key lives on the bunker (a phone app, a hardware signer, a signing service). You connect with a URL:

```java
// bunker://<bunker-pubkey>?relay=wss://...&secret=...
BunkerUrl bunker = BunkerUrl.parse("bunker://...");
// or the nostrconnect:// variant, same parameters plus an optional name
NostrconnectUrl nc = NostrconnectUrl.parse("nostrconnect://...");

NostrNIP46Signer signer = new NostrNIP46Signer(appMetadata, clientKeyPair);
```

The `secret` in the URL pairs this app with the bunker; the user approves the pairing on the bunker's side. After that, `sign()` works exactly like the local signer, except each call is a Nostr round-trip to the bunker.

Two ways to establish the connection:

```java
// you already have a bunker URL (pasted, scanned, from NIP-05...)
signer.connect(BunkerUrl.parse("bunker://...")).get();

// or: generate a nostrconnect:// URL, show it to the user, wait for approval
signer.listen(List.of("wss://relay.ngengine.org"),
    url -> showQrCode(url.toString()),       // user scans it with their bunker app
    Duration.ofMinutes(5)).get();
```

`Nip46AppMetadata` describes your app to the bunker (name, url, icon, and the permissions it requests via `addPerm`). For anything beyond signing, `sendRPC(method, params, timeout)` issues raw NIP-46 RPC calls.

## Encrypted keys (NIP-49)

A `ncryptsec1...` string is a private key encrypted with a passphrase. Decrypting is async because key derivation is deliberately slow:

```java
// read an encrypted key (needs the user's passphrase)
NostrPrivateKey priv = NostrPrivateKey.fromNcryptsec("ncryptsec1...", passphrase).get();

// write one: store THIS, not the raw nsec
String stored = priv.asNcryptsec(passphrase).get();
```

Rule of thumb: if your app persists a key anywhere (disk, database, preferences), persist the `ncryptsec` form. The raw `nsec` should only exist in memory, for the shortest time possible.

## Encrypted messages (NIP-44 / NIP-04)

Direct messages are encrypted to the recipient's public key. Through the signer it's two calls; NIP-44 is the modern format and the default:

```java
String cipher = signer.encrypt("meet at dawn", recipientPubkey).get();
String plain  = signer.decrypt(cipher, senderPubkey).get();
```

Kind 4 events carry NIP-04 ciphertext (legacy), kind 44 carry NIP-44. If you ever need the primitives directly instead of going through a signer, `Nip04` has static `encrypt`/`decrypt` (sync and async variants), and `Nip44` exposes the lower-level conversation-key API. For normal app code, stick to the signer methods.

## Where next

- [NIPs](nips.md) - guides for the supported protocol extensions
- [Concepts](concepts.md) - keys, events, signing, filters
