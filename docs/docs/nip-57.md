---
title: Tip with zaps
---

# Tip with zaps

To tip a note or profile, first request a Lightning invoice from the recipient's LNURL-pay provider. Include a signed zap request so the payment can be associated with the Nostr event or account. After payment, the provider publishes a kind-9735 receipt to the relays named in that request.

## Zapping an event

```java
// 1. Build/sign the kind-9734 request and send it to the recipient's
//    provider while requesting invoices (one task per zap target).
List<AsyncTask<ZapInvoice>> invoices = Nip57.getZapInvoices(
    pool, payerSigner, payerMetadata, eventToZap, 21_000, "great post!");

ZapInvoice inv = invoices.get(0).await();
if (inv == null) throw new IllegalStateException("recipient has no payment address");

// 2. pay the Lightning invoice with your wallet
PayResponse payment = wallet.payInvoice(inv.getInvoice(), null, null).await();

// 3. Do not publish inv.getZapRequest() yourself. The LNURL-pay provider
//    publishes the kind-9735 receipt after it observes payment.
```

Amounts are in millisatoshis. To tip a profile instead of an event, use the overload of `getZapInvoices` that takes a bare public key. If you already know the Lightning address, use the lower-level overload that works directly from an `LnUrl`. If the target has no usable Lightning address, you get a `MalformedZapTargetException`.

## Reading zaps

```java
// zap receipts (kind 9735) for an event, a recipient, a sender...
List<SignedNostrEvent> receipts =
    Nip57.getZaps(pool, eventToZap, null, null, null, null).await();
```

When displaying received zaps, validate the receipt against the invoice, request and provider you expect:

```java
ZapReceipt receipt = Nip57.parseAndValidateZapReceipt(
    receiptEvent,
    expectedInvoice,   // the ZapInvoice you paid, or null to skip
    expectedProvider,  // trusted provider pubkey; may come from expectedInvoice
    expectedRequest,   // the zap request you signed, or null
    expectedPreimage,  // payment preimage, or null
    expectedLnUrl,     // recipient Lightning address, or null
    expectedSender)    // who paid, or null
    .await();
```

Supply either `expectedInvoice` with its provider public key, or an independently trusted `expectedProvider`. The task fails if neither supplies a provider key. Do not take that trusted key from the unverified receipt itself. Other expectations may be `null` when you cannot supply them.

Validation checks the receipt signature, the embedded request signature and their relationship to the provider and invoice. To read the receipt's details, use `getBolt11()`, `getPreimage()`, `getSender()`, `getRecipient()`, and `getZappedEventCoordinates()`. Read the amount from the embedded request: `receipt.getZapRequestEvent().getAmountMsats()`.

For the payment step, see [wallet operations](wallets.md). Parsing a receipt alone is not proof of payment; supply the invoice and request expectations you have when validating it.
