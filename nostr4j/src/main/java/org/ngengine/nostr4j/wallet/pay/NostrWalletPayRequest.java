package org.ngengine.nostr4j.wallet.pay;

import java.time.Instant;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletPayRequest (
    @Nonnull String invoice,
    @Nullable Long amountMsats
        
){
    
}
