package org.ngengine.nostr4j.wallet.invoice;

import jakarta.annotation.Nullable;

public record NostrWalletLookupInvoiceRequest (
    @Nullable String paymentHash,    
    @Nullable String invoice
){
    
}
