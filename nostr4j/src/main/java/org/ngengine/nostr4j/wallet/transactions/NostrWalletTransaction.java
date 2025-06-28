package org.ngengine.nostr4j.wallet.transactions;

import java.time.Instant;
import java.util.Map;

import org.ngengine.nostr4j.wallet.invoice.NostrWalletInvoiceType;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletTransaction(
    @Nonnull NostrWalletTransactionType type,
    @Nullable String invoice,
    @Nullable String description,
    @Nullable String descriptionHash,
    @Nullable String preimage,
    @Nonnull String paymentHash,
    long amountMsats,
    long feesPaid,
    @Nonnull Instant createdAt,
    @Nullable Instant expiresAt,
    @Nullable Instant settledAt,
    @Nullable Map<String, Object> metadata
) {

  
    
}
