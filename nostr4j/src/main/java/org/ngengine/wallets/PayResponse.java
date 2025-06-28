package org.ngengine.wallets;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record PayResponse (
    @Nonnull String preimage,
    @Nullable Long feesPaid,
    @Nullable String id
){
    
}
