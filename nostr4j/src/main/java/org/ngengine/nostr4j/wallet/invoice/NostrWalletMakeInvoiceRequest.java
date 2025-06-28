package org.ngengine.nostr4j.wallet.invoice;

import java.time.Duration;

import jakarta.annotation.Nullable;

public record NostrWalletMakeInvoiceRequest(
    long amountMsats,
    @Nullable String description,
    @Nullable String descriptionHash,
    @Nullable Duration expiry
) {
    
}
