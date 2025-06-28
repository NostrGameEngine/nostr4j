package org.ngengine.nostr4j.wallet;

import java.time.Instant;
import java.util.List;

import org.ngengine.nostr4j.wallet.info.NostrWalletBalance;
import org.ngengine.nostr4j.wallet.info.NostrWalletInfo;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletInvoice;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletLookupInvoiceRequest;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletMakeInvoiceRequest;
import org.ngengine.nostr4j.wallet.keysend.NostrWalletPayKeysendRequest;
import org.ngengine.nostr4j.wallet.keysend.NostrWalletPayKeysendResponse;
import org.ngengine.nostr4j.wallet.pay.NostrWalletPayRequest;
import org.ngengine.nostr4j.wallet.pay.NostrWalletPayResponse;
import org.ngengine.nostr4j.wallet.transactions.NostrWalletListTransactionsRequest;
import org.ngengine.nostr4j.wallet.transactions.NostrWalletTransaction;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nullable;

// TODO: support multi-target payments
public interface NostrWallet {
    public default AsyncTask<NostrWalletPayResponse> pay(NostrWalletPayRequest req) {
        return pay(req, null);
    }

    public AsyncTask<NostrWalletPayResponse> pay(NostrWalletPayRequest req, @Nullable Instant expiresAt);
    // public AsyncTask<List<NostrWalletPayResponse>>
    // pay(List<NostrWalletPayRequest> req);

    public default AsyncTask<NostrWalletPayKeysendResponse> keysend(NostrWalletPayKeysendRequest req) {
        return keysend(req, null);
    }

    public AsyncTask<NostrWalletPayKeysendResponse> keysend(NostrWalletPayKeysendRequest req,
            @Nullable Instant expiresAt);
    // public AsyncTask<List<NostrWalletPayKeysendResponse>>
    // keysend(List<NostrWalletPayKeysendRequest> req);

    public default AsyncTask<NostrWalletInvoice> invoice(NostrWalletMakeInvoiceRequest req) {
        return invoice(req, null);
    }

    public AsyncTask<NostrWalletInvoice> invoice(NostrWalletMakeInvoiceRequest req, @Nullable Instant expiresAt);

    public default AsyncTask<NostrWalletInvoice> lookup(NostrWalletLookupInvoiceRequest req) {
        return lookup(req, null);
    }

    public AsyncTask<NostrWalletInvoice> lookup(NostrWalletLookupInvoiceRequest req, @Nullable Instant expiresAt);

    public default AsyncTask<List<NostrWalletTransaction>> listTransactions(NostrWalletListTransactionsRequest req) {
        return listTransactions(req, null);
    }

    public AsyncTask<List<NostrWalletTransaction>> listTransactions(NostrWalletListTransactionsRequest req,
            @Nullable Instant expiresAt);

    public default AsyncTask<NostrWalletBalance> getBalance() {
        return getBalance(null);
    }

    public AsyncTask<NostrWalletBalance> getBalance(@Nullable Instant expiresAt);

    public default AsyncTask<NostrWalletInfo> getInfo() {
        return getInfo(null);
    }

    public AsyncTask<NostrWalletInfo> getInfo(@Nullable Instant expiresAt);
}
