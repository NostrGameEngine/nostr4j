package org.ngengine.wallets.nip47.keysend;

import jakarta.annotation.Nonnull;

public record NWCTLVRecord (
    long type,
    @Nonnull String value
){
    
}
