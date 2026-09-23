---
title: NIP-07 browser signing
---

# NIP-07 - Browser extension signing

`NostrNIP07Signer` delegates identity operations to `window.nostr`. The extension keeps the private key and owns its approval UI.

```java
NostrSigner signer = new NostrNIP07Signer();

if (!signer.isAvailable().get()) {
    throw new IllegalStateException("No NIP-07 extension available");
}

NostrPublicKey me = signer.getPublicKey().get();
SignedNostrEvent event = signer.sign(unsigned).get();
```

The same signer exposes NIP-04 and NIP-44 encryption when the installed extension supports them. Availability does not guarantee every optional method; handle a rejected async task and explain which extension capability is required.

Construct this signer only in a browser/TeaVM target. Do not ask users to paste an `nsec` into a web page as a fallback.
