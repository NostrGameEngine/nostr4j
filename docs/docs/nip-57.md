---
title: NIP-57 Zaps
---

# NIP-57 - Zaps

A zap is a Lightning payment attached to a Nostr event or profile. The payer signs a zap request (kind 9734), sends it to the recipient's LNURL-pay provider while requesting an invoice, then pays that invoice. The provider publishes the receipt (kind 9735) to the relays named in the request. `Nip57` builds and validates that flow; paying is up to your wallet (see [Wallets](wallets.md)).

## Zapping an event

```java
// 1. Build/sign the kind-9734 request and send it to the recipient's
//    provider while requesting invoices (one task per zap target).
List<AsyncTask<ZapInvoice>> invoices = Nip57.getZapInvoices(
    pool, payerSigner, payerMetadata, eventToZap, 21_000, "great post!");

ZapInvoice inv = invoices.get(0).get();
if (inv == null) throw new IllegalStateException("recipient has no payment address");

// 2. pay the Lightning invoice with your wallet
PayResponse payment = wallet.payInvoice(inv.getInvoice(), null, null).get();

// 3. Do not publish inv.getZapRequest() yourself. The LNURL-pay provider
//    publishes the kind-9735 receipt after it observes payment.
```

Amounts are in **millisats**. There is an overload of `getZapInvoices` that zaps a bare public key instead of an event (for profile tips), and a lower-level one that works directly from an `LnUrl` when you already know the Lightning address. If the target has no usable Lightning address, it throws `MalformedZapTargetException`.

## Reading zaps

```java
// zap receipts (kind 9735) for an event, a recipient, a sender...
List<SignedNostrEvent> receipts =
    Nip57.getZaps(pool, eventToZap, null, null, null, null).get();
```

Each receipt can be parsed and validated against what you expect:

```java
ZapReceipt receipt = Nip57.parseAndValidateZapReceipt(
    receiptEvent,
    expectedInvoice,   // the ZapInvoice you paid, or null to skip
    expectedProvider,  // provider pubkey, or null
    expectedRequest,   // the zap request you published, or null
    expectedPreimage,  // payment preimage, or null
    expectedLnUrl,     // recipient Lightning address, or null
    expectedSender)    // who paid, or null
    .get();
```

Pass `null` for an expectation you cannot supply. The receipt exposes `getBolt11()`, `getPreimage()`, `getSender()`, `getRecipient()`, and `getZappedEventCoordinates()`. The amount lives on the embedded request: `receipt.getZapRequestEvent().getAmountMsats()`.

> **Note**
> Never trust a zap receipt at face value in anything that moves real value: validate it against the invoice you actually paid. `parseAndValidateZapReceipt` exists for exactly that.

## Where next

- [Wallets](wallets.md) - pay the invoice over NWC
- [NIPs index](nips.md)
