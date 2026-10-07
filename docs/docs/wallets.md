---
title: Wallets and NWC
---

# Wallets & NWC

<a class="doc-run-button" href="../demos.html#wallet">Run wallet demo</a>

To read a balance, create an invoice or pay one, ask the user for a Nostr Wallet Connect connection. They create it in their wallet and choose what your app may do. The connection string contains the secret authorizing those operations; you don't need the wallet's account keys.

## Connecting

Pass the `nostr+walletconnect://` URI to `NWCWallet`, with either an existing pool or a new one owned by the wallet:

```java
NWCUri uri = new NWCUri("nostr+walletconnect://...");

NWCWallet wallet = new NWCWallet(pool, uri);
// or: NWCWallet wallet = new NWCWallet(uri);   // own pool

wallet.waitForReady().await();   // wallet service answered, ready to use
```

Inspect the connection's relay list, public key and other fields through `NWCUri` if needed; avoid logging the secret.

## Reading state

```java
long balanceMsats = wallet.getBalance(null).await();   // millisats
WalletInfo info = wallet.getInfo(null).await();        // advertised capabilities
List<String> methods = wallet.getSupportedMethods().await();
```

Check `isMethodSupported(...)` before offering an operation in your UI. If you already have a connection and only need its current readiness state, use `isReady()`.

## Paying and invoicing

```java
// pay a Lightning invoice
PayResponse res = wallet.payInvoice(bolt11, null, null).await();

// create an invoice on the wallet
InvoiceProperties request = new InvoiceProperties(
    21_000L, "Nostr4J demo", null, Duration.ofMinutes(10));
InvoiceData inv = wallet.makeInvoice(request, null).await();

// spontaneous payment without an invoice
NWCKeysendResponse sent = wallet
    .keySend(null, amountMsats, recipientPubkeyHex, null, null, null)
    .await();

// look things up
InvoiceData seen = wallet.lookupInvoice(paymentHash, null, null).await();
List<TransactionInfo> txs =
    wallet.listTransactions(from, until, 20, 0, false, null, null).await();
```

Amounts are in millisatoshis. The final argument to these request methods is an optional expiry time (`Instant`, or `null` for none). Close the wallet when the connection is no longer needed.

With `new NWCWallet(uri)`, the wallet owns its pool and disconnects those relays when closed. With `new NWCWallet(pool, uri)`, your application owns the shared pool; close it after its other users have finished. Closing a wallet with a supplied pool does not close that pool.

For shared application code, use the `Wallet` interface and keep NWC setup at the edge of your app. To attach a payment to a Nostr note or profile, follow the [zap flow](nip-57.md).
