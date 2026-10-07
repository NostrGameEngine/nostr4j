---
title: Signers
---

# Signers

You can sign an event with a key held by your app, ask a browser extension, or send the request to a remote signer. All three options implement `NostrSigner`, so the rest of your application can use the same calls:

```java
signer.sign(unsignedEvent).compose(pool::publish);
```

Signing returns an `AsyncTask`. An extension or remote signer may wait for the user to approve the request.


## Sign with a local key

For a bot, server or temporary account, wrap a `NostrKeyPair` in a local signer:

```java
NostrKeyPair keys = new NostrKeyPair();
NostrSigner signer = new NostrKeyPairSigner(keys);
```

This example creates a new identity. To keep using an existing account, [load its key](keys.md) and pass that pair to the constructor instead. Close the signer when you're done with it, then close any key pair your application owns. Closing a local signer does not close its key pair.

Reuse the signer when signing several events with the same identity. It prepares reusable public signing state while keeping the private key owned by the supplied pair; closing that pair prevents further signing.

## Ask a browser extension

If the user has a Nostr extension, you can request signatures without asking them to give your app a private key:

```java
NostrSigner signer = new NostrNIP07Signer();
if (!signer.isAvailable().await()) {
    throw new IllegalStateException("No NIP-07 extension available");
}
NostrPublicKey identity = signer.getPublicKey().await();
```

To confirm an extension is present, check for the browser's `window.nostr` provider (as the example does with `isAvailable()`). Even when present, the extension approves or rejects each request, and API support varies between extensions.

## Connect to a remote NIP-46 signer

Use this when the account key must stay on another device or service. Connect over an encrypted channel using a separate client key:

```java
NostrNIP46Signer signer = new NostrNIP46Signer(appMetadata, clientKeyPair);
signer.connect(BunkerUrl.parse(bunkerUrl)).await();
```

Get `bunkerUrl` from the user: it carries the signer address, the relays to use, and possibly a pairing secret. Keep that secret out of logs. Describe your app with `Nip46AppMetadata`: name, URL, icon and requested permissions.

You can also start pairing from your app and show the resulting `nostrconnect://` URL as a QR code:

```java
signer.listen(List.of("wss://relay.ngengine.org"),
    url -> showQrCode(url.toString()),
    Duration.ofMinutes(5)).await();
```

After pairing, call `sign` just as you would with a local signer. Set a request timeout with `setRequestsTimeout` and close the signer when the session ends.

Closing the remote signer disconnects its private relay connections, closes its subscription and cancels pending requests. Close any client key pair owned by your application separately.

## Other operations

Use `getPublicKey()` to find out which identity you are signing with. To encrypt or decrypt, NIP-44 is the default; pass `EncryptAlgo.NIP04` when you need legacy compatibility.

If a relay requires proof of work, add it during signing with `powSign(event, difficulty)`. To reach an operation the common interface doesn't cover, use raw NIP-46 RPC through `sendRPC`.
