---
title: Wallets and NWC
---

# Wallets & NWC

Nostr Wallet Connect (NIP-47) lets your app talk to the user's Lightning wallet over Nostr. The user pastes a connection string; your app never touches their keys - it only holds the NWC secret from the URI, scoped to what the wallet service authorized.

## Connecting

Parse the `nostr+walletconnect://` URI and hand it to `NWCWallet`. Give it your pool, or let it create its own:

```java
NWCUri uri = new NWCUri("nostr+walletconnect://...");

NWCWallet wallet = new NWCWallet(pool, uri);
// or: NWCWallet wallet = new NWCWallet(uri);   // own pool

wallet.waitForReady().get();   // wallet service answered, ready to use
```

`NWCUri` also exposes the parts separately (`getPubkey()`, `getRelays()`, `getSecret()`, `getLud16()`) if you need them. The wallet speaks to the wallet service over Nostr with NIP-44 encrypted request/response events.

## Reading state

```java
long balanceMsats = wallet.getBalance(null).get();   // millisats
WalletInfo info = wallet.getInfo(null).get();        // advertised capabilities
List<String> methods = wallet.getSupportedMethods().get();
```

`isMethodSupported(...)` checks a single method before you call it. `isReady()` is the non-blocking version of `waitForReady()`.

## Paying and invoicing

```java
// pay a Lightning invoice
PayResponse res = wallet.payInvoice(bolt11, null, null).get();

// create an invoice on the wallet
InvoiceProperties request = new InvoiceProperties(
    21_000L, "Nostr4J demo", null, Duration.ofMinutes(10));
InvoiceData inv = wallet.makeInvoice(request, null).get();

// spontaneous payment without an invoice
NWCKeysendResponse sent = wallet
    .keySend(null, amountMsats, recipientPubkeyHex, null, null, null)
    .get();

// look things up
InvoiceData seen = wallet.lookupInvoice(paymentHash, null, null).get();
List<TransactionInfo> txs =
    wallet.listTransactions(from, until, 20, 0, false, null, null).get();
```

The last argument everywhere is an optional request expiry (`Instant`, or `null` for none). `NWCWallet` implements the `Wallet` interface, so code written against `Wallet` works with any present or future implementation. `close()` releases the wallet.

## Design notes

- Your app only ever holds the NWC secret, never the wallet's keys. Never log connection strings.
- Amounts are in millisats throughout.
- Signing requests are NIP-46-shaped; the same `NostrSigner` ideas from [Keys & signers](signers.md) apply.

## Where next

- [NIP-57](nip-57.md) - zaps: build the invoice, pay it here
- [NIPs index](nips.md)
