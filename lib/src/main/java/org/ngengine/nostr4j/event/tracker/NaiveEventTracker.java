package org.ngengine.nostr4j.event.tracker;

import java.util.HashSet;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class NaiveEventTracker implements EventTracker {
    public HashSet<String> seenEvents = new HashSet<>();

    @Override
    public boolean seen(SignedNostrEvent event) {
        return seenEvents.add(event.getIdentifier().id);
    }

    public void clear(){
        seenEvents.clear();
    }    

     @Override
    public void tuneFor(NostrSubscription sub) {
        
    }
}
