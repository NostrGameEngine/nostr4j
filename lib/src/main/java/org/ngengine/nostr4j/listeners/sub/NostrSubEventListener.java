package org.ngengine.nostr4j.listeners.sub;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public interface NostrSubEventListener extends NostrSubListener {
    public void onSubEvent(NostrSubscription sub, SignedNostrEvent event, boolean stored);
}
