package org.ngengine.nostr4j.wallet.pay;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletPayResponse (
    @Nonnull String preimage,
    @Nullable Long feesPaid,
    @Nullable String id
){
    
}
