package org.ngengine.wallets.nip47.keysend;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NWCKeysendResponse (
    @Nonnull String preimage,
    @Nullable Long feesPaid
) {
    
}
