package org.ngengine.nostr4j.listeners.sub;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public interface NostrSubStatusListener extends NostrSubListener {
    public void onSubStatus(NostrSubscription sub, SignedNostrEvent event, boolean success,String message);
}
