package org.ngengine.nostr4j.event.tracker;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class PassthroughEventTracker implements EventTracker {
    @Override
    public boolean seen(SignedNostrEvent event) {
        return false;
    }    

     @Override
    public void tuneFor(NostrSubscription sub) {
        
    }
}
