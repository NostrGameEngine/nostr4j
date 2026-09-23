---
title: Connect through NWC
---

# Connect through NWC

Ask the user to create a Nostr Wallet Connect connection in their wallet and supply its `nostr+walletconnect://` URI. Once the wallet is ready, query its balance and supported operations:

```java
NWCUri uri = new NWCUri(connectionString);
NWCWallet wallet = new NWCWallet(pool, uri);
try {
    wallet.waitForReady().await();
    long balanceMsats = wallet.getBalance().await();
    boolean canPay = wallet
        .isMethodSupported(Wallet.Methods.payInvoice).await();
} finally {
    wallet.close();
}
```

Use `try/finally` to close the wallet: `NWCWallet` does not implement `AutoCloseable`.

The connection URI contains the secret that authorizes your app, so keep it out of logs and use protected storage if you retain it. Amounts are in millisatoshis. See [wallet operations](wallets.md) for invoices and payments.
