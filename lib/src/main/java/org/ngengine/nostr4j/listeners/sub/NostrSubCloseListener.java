package org.ngengine.nostr4j.listeners.sub;

import org.ngengine.nostr4j.NostrSubscription;

public interface NostrSubCloseListener extends NostrSubListener{

    public void onSubClose(NostrSubscription sub, String reason);
    
}
