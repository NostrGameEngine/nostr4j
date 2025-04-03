package org.ngengine.nostr4j.listeners.sub;

import org.ngengine.nostr4j.NostrSubscription;

public interface NostrSubEoseListener extends NostrSubListener{
    public void onSubEose(NostrSubscription sub);   
}
