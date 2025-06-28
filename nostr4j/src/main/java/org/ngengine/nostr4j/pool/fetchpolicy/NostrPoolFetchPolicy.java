package org.ngengine.nostr4j.pool.fetchpolicy;

import java.util.List;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.sub.NostrSubAllListener;

public interface NostrPoolFetchPolicy  {
    public NostrSubAllListener getListener(NostrSubscription sub,List<SignedNostrEvent> events,Runnable end);
}
