package org.ngengine.nostr4j.wallet.info;

import java.util.List;

public record NostrWalletInfo(
    String alias,
    String color,
    String pubkey,
    String network,
    int blockHeight,
    String blockHash,
    List<String> methods,
    List<String> notifications
) {
    
}
