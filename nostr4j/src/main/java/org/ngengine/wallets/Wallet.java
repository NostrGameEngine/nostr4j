package org.ngengine.wallets;

import java.time.Instant;
import java.util.List;

import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

// TODO: support multi-target payments
public interface Wallet {
    public enum Methods {
        payInvoice,
        makeInvoice,
        lookupInvoice,
        listTransactions,
        getBalance,
        getInfo
    }


    /**
     * Pay a lightning invoice
     * @param invoice the lightning invoice to pay
     * @param amountMsats the amount in millisatoshis to pay, or null to use the invoice amount
     * @return an AsyncTask that resolves to a PayResponse containing the payment result
     */
    public default AsyncTask<PayResponse> payInvoice(
        @Nonnull String invoice,
        @Nullable Long amountMsats
    ) {
        return payInvoice(invoice, amountMsats, null);
    }

    /**
     * Pay a lightning invoice
     * @param invoice the lightning invoice to pay
     * @param amountMsats the amount in millisatoshis to pay, or null to use the invoice amount
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return an AsyncTask that resolves to a PayResponse containing the payment result
     */
    public AsyncTask<PayResponse> payInvoice(
        @Nonnull String invoice,
        @Nullable Long amountMsats, 
        @Nullable Instant expireRequestAt
    );
 


    /**
     * Create a lightning invoice
     * @param req the properties for the invoice to create
     * @return an AsyncTask that resolves to an InvoiceData containing the created invoice
     */
    public default AsyncTask<InvoiceData> makeInvoice(InvoiceProperties req) {
        return makeInvoice(req, null);
    }

    /**
     * Create a lightning invoice
     * @param req the properties for the invoice to create
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return
     */
    public AsyncTask<InvoiceData> makeInvoice(InvoiceProperties req, @Nullable Instant expireRequestAt);


    /**
     * Lookup an invoice by payment hash or invoice string.
     * At least one of paymentHash or invoice must be provided.
     * @param paymentHash   the payment hash to lookup, or null if not available
     * @param invoice the invoice string to lookup, or null if not available
     * @return an AsyncTask that resolves to an InvoiceData containing the invoice details
     */
    public default AsyncTask<InvoiceData> lookupInvoice(
        @Nullable String paymentHash,    
        @Nullable String invoice
    ) {
        return lookupInvoice(paymentHash,invoice, null);
    }

    /**
     * Lookup an invoice by payment hash or invoice string.
     * @param paymentHash  the payment hash to lookup, or null if not available
     * @param invoice the invoice string to lookup, or null if not available
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return an AsyncTask that resolves to an InvoiceData containing the invoice details
     */
    public AsyncTask<InvoiceData> lookupInvoice(  
        @Nullable String paymentHash,    
        @Nullable String invoice, 
        @Nullable Instant expireRequestAt
    );



    /**
     * List transactions in the wallet.
     * @param from the start time to filter transactions from, or null for no start time
     * @param until the end time to filter transactions until, or null for no end time
     * @param limit the maximum number of transactions to return, or null for no limit
     * @param offset the offset to start returning transactions from, or null for no offset
     * @param includeUnpaid whether to include unpaid transactions, or false to exclude them
     * @param type the type of transactions to filter by, or null for all types
     * @return an AsyncTask that resolves to a List of TransactionInfo containing the transactions
     */
    public default AsyncTask<List<TransactionInfo>> listTransactions(
        @Nullable Instant from,
        @Nullable Instant until,
        @Nullable Integer limit,
        @Nullable Integer offset,
        boolean includeUnpaid,
        @Nullable TransactionType type
    ) {
        return listTransactions(
            from,
            until,
            limit,
            offset,
            includeUnpaid,
            type,
            null
        );
    }

    /**
     * List transactions in the wallet.
     * @param from the start time to filter transactions from, or null for no start time
     * @param until the end time to filter transactions until, or null for no end time
     * @param limit the maximum number of transactions to return, or null for no limit
     * @param offset the offset to start returning transactions from, or null for no offset
     * @param includeUnpaid whether to include unpaid transactions, or false to exclude them
     * @param type the type of transactions to filter by, or null for all types
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return an AsyncTask that resolves to a List of TransactionInfo containing the transactions
     */
    public AsyncTask<List<TransactionInfo>> listTransactions(
        @Nullable Instant from,
        @Nullable Instant until,
        @Nullable Integer limit,
        @Nullable Integer offset,
        boolean includeUnpaid,
        @Nullable TransactionType type,
        @Nullable Instant expireRequestAt
    );


    /**
     *  Get the msats balance of the wallet.
     * @return an AsyncTask that resolves to the balance in millisatoshis
     */
    public default AsyncTask<Long> getBalance() {
        return getBalance(null);
    }

    /**
     * Get the msats balance of the wallet.
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return an AsyncTask that resolves to the balance in millisatoshis
     */
    public AsyncTask<Long> getBalance(@Nullable Instant expireRequestAt);

    /**
     * Get information about the wallet.
     * @return an AsyncTask that resolves to a WalletInfo containing the wallet details
     */
    public default AsyncTask<WalletInfo> getInfo() {
        return getInfo(null);
    }

    /**
     * Get information about the wallet.
     * @param expireRequestAt an optional Instant to expire the request at, or null for no expiration
     * @return an AsyncTask that resolves to a WalletInfo containing the wallet details
     */
    public AsyncTask<WalletInfo> getInfo(@Nullable Instant expireRequestAt);



    /**
     * Check if the wallet is ready to use.
     * @return true if the wallet is ready, false otherwise
     */
    public boolean isReady();

    /**
     * Wait for the wallet to be ready.
     * @return an AsyncTask that resolves to true if the wallet is ready, false if it failed to become ready
     */
    public AsyncTask<Boolean> waitForReady();

    /**
     * Check if a specific method is supported by the wallet.
     * @param method the method to check support for
     * @return an AsyncTask that resolves to true if the method is supported, false otherwise
     */
    public AsyncTask<Boolean> isMethodSupported(
        @Nonnull Methods method
    );
}
