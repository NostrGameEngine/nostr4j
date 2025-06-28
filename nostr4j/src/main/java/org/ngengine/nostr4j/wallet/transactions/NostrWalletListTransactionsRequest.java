package org.ngengine.nostr4j.wallet.transactions;

import jakarta.annotation.Nullable;

public record NostrWalletListTransactionsRequest (
    @Nullable Long from,
    @Nullable Long until,
    @Nullable Integer limit,
    @Nullable Integer offset,
    boolean includeUnpaid,
    @Nullable NostrWalletTransactionType type
){
    
}
