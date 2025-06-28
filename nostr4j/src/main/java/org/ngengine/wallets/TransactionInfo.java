package org.ngengine.wallets;

import java.time.Instant;
import java.util.Map;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record TransactionInfo(
    @Nonnull TransactionType type,
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
