package org.ngengine.nostr4j.wallet.invoice;

import java.time.Instant;
import java.util.Map;

import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public record NostrWalletInvoice(
    @Nonnull NostrWalletInvoiceType type,
    @Nullable String invoice,
    @Nullable String description,
    @Nullable String descriptionHash,
    @Nullable String preimage,
    @Nonnull String paymentHash,
    long amountMsats,
    long feesPaid,
    @Nonnull Instant createdAt,
    @Nullable Instant expiresAt,
    @Nullable Instant settledAt,
    @Nullable Map<String, Object> metadata
) {
    public String comment(){
        Object comment = null;
        if(metadata!=null){
            comment = metadata.get("comment");
        }
        if(comment == null){
            comment=description();
        }
        return NGEUtils.safeString(comment);
    }
}
