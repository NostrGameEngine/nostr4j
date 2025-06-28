package org.ngengine.wallets;

import java.time.Duration;

import jakarta.annotation.Nullable;

public record InvoiceProperties(
    long amountMsats,
    @Nullable String description,
    @Nullable String descriptionHash,
    @Nullable Duration expiry
) {
    
}
