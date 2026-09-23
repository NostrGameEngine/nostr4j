---
title: NIP-47 wallet connect
---

# NIP-47 - Nostr Wallet Connect

NWC turns a `nostr+walletconnect://` capability into the wallet-neutral `Wallet` interface.

```java
NWCUri uri = new NWCUri(connectionString);
NWCWallet wallet = new NWCWallet(pool, uri);
try {
    wallet.waitForReady().get();
    long balanceMsats = wallet.getBalance().get();
    boolean canPay = wallet
        .isMethodSupported(Wallet.Methods.payInvoice).get();
} finally {
    wallet.close();
}
```

`NWCWallet` does not implement `AutoCloseable`, so use an ordinary `try/finally` rather than Java's try-with-resources.

The URI contains a secret and relay list. Never log or persist it unless your application has an encrypted credential store. Use narrowly scoped connections and millisatoshi amounts throughout. See [Wallets & NWC](wallets.md) for all supported methods.
