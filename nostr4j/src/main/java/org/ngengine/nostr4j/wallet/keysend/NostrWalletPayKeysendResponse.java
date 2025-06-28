package org.ngengine.nostr4j.wallet.keysend;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletPayKeysendResponse (
    @Nonnull String preimage,
    @Nullable Long feesPaid
) {
    
}
