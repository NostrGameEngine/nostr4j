package org.ngengine.nostr4j.event.tracker;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

// not thread safe
public interface EventTracker {
    boolean seen(SignedNostrEvent event);
    void tuneFor(NostrSubscription sub);

}