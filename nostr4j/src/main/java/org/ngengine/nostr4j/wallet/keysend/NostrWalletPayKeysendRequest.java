package org.ngengine.nostr4j.wallet.keysend;

import java.util.List;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletPayKeysendRequest(
    @Nullable String id,
    long amountMsats,    
    @Nonnull String pubkey,
    @Nullable String preimage,
    @Nullable List<NostrWalletTLVRecord> tlvRecords
) {
    
}
