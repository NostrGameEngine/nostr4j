package org.ngengine.nostr4j.listeners.sub;

import java.util.List;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.platform.AsyncTask;

public interface NostrSubOpenListener extends NostrSubListener {
    /**
     * Called when a subscription is opened.
     * <p>
     * This method is invoked when a subscription is successfully opened.
     * </p>
     * 
     * @param sub The subscription that was opened
     */
     void onSubOpen(NostrSubscription sub);
    
}
