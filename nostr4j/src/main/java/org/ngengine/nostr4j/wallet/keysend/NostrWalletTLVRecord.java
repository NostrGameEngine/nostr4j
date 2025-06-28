package org.ngengine.nostr4j.wallet.keysend;

import jakarta.annotation.Nonnull;

public record NostrWalletTLVRecord (
    long type,
    @Nonnull String value
){
    
}
