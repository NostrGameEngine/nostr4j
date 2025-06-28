package org.ngengine.wallets;

import java.util.List;

public record WalletInfo(
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
