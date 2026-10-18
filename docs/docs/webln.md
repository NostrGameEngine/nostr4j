---
title: Connect a WebLN wallet
---

# Connect a WebLN wallet

`WebLNWallet` connects your browser app to the user's WebLN wallet. It uses the same `Wallet` interface as [Nostr Wallet Connect](wallets.md). The user needs a wallet extension or browser with WebLN support.

## Connect

Check availability:

```java
WebLNWallet wallet = new WebLNWallet();
wallet.isAvailable().then(available -> {
    System.out.println(available ? "Browser wallet found" : "No browser wallet");
    return null;
});
```

`isAvailable()` checks for a provider without requesting permissions. `waitForReady()` initializes the wallet connection and requests access, which may require approval in the wallet:

```java
wallet.waitForReady().then(ready -> {
    System.out.println("Wallet ready: " + ready);
    return null;
}).catchException(error -> {
    System.err.println("Wallet connection failed: " + error);
});
```

## Offer supported wallet actions

Check `isMethodSupported(Wallet.Methods.payInvoice)` or the relevant method before offering an action.

Wallet amounts use millisatoshis (1,000 millisatoshis = 1 satoshi). WebLN uses satoshis, so the adapter requires amounts that convert to whole satoshis.
