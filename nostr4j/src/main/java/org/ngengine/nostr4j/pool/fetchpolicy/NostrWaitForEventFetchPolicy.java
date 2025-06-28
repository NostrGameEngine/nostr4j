package org.ngengine.nostr4j.pool.fetchpolicy;

import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.sub.NostrSubAllListener;
import static org.ngengine.platform.NGEUtils.dbg;

public class NostrWaitForEventFetchPolicy implements NostrPoolFetchPolicy{
    private static final Logger logger = Logger.getLogger(NostrPool.class.getName());
    private final Predicate<SignedNostrEvent> filter;

    public static NostrWaitForEventFetchPolicy get(Predicate<SignedNostrEvent> filter) {
        return new NostrWaitForEventFetchPolicy(filter);
    }

    public NostrWaitForEventFetchPolicy(Predicate<SignedNostrEvent> filter){
        this.filter = filter;
    }

    @Override
    public NostrSubAllListener getListener(NostrSubscription sub, List<SignedNostrEvent> events, Runnable end) {
        return new NostrSubAllListener() {

            @Override
            public void onSubEvent(SignedNostrEvent e, boolean stored) {
                assert dbg(() -> {
                    logger.finer("fetch event " + e + " for subscription " + sub.getId());
                });
                if(filter.test(e)){
                    events.add(e);
                    end.run();
                }
            }

            @Override
            public void onSubClose(List<String> reason) {
                assert dbg(() -> {
                    logger.fine("fetch close " + reason + " for subscription " + sub.getId());
                });
                end.run();

            }

            @Override
            public void onSubEose(NostrRelay relay, boolean all) {
                
            }
            
        };
    }
    
}
