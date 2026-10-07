---
title: Sign with a browser extension
---

# Sign with a browser extension

Check for a NIP-07 extension before asking the user to sign. Their private key stays in the extension, which handles the approval prompt:

```java
NostrSigner signer = new NostrNIP07Signer();

if (!signer.isAvailable().await()) {
    throw new IllegalStateException("No NIP-07 extension available");
}

NostrPublicKey me = signer.getPublicKey().await();
SignedNostrEvent event = signer.sign(unsigned).await();
```

You can also request NIP-04 or NIP-44 encryption if the extension supports it. Handle a rejected task even after a successful availability check: the user may refuse, or the requested method may be unavailable.

Use this signer in the browser. For other environments, or users without an extension, offer one of the other [signing options](signers.md).
