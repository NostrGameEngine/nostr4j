package org.ngengine.nostr4j.pool.fetchpolicy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
    private AtomicInteger count = new AtomicInteger(0);
    private final int numEventsToWait;
    private final boolean endOnEose;

    public static NostrWaitForEventFetchPolicy get(
        Predicate<SignedNostrEvent> filter,
        int numEventsToWait,
        boolean endOnEose
    ) {
        return new NostrWaitForEventFetchPolicy(filter, numEventsToWait, endOnEose);
    }


    public NostrWaitForEventFetchPolicy(
        Predicate<SignedNostrEvent> filter, 
        int numEventsToWait,
        boolean endOnEose
    ) {
        this.filter = filter;
        this.numEventsToWait = numEventsToWait;
        this.endOnEose = endOnEose;
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
                    if(count.incrementAndGet()>= numEventsToWait) {
                        end.run();
                    }
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
                if(endOnEose&&all){
                    end.run();
                    assert dbg(() -> {
                        logger.fine("fetch eose for fetch " + sub.getId() + " with received events: " + events);
                    });

                }
                
            }
            
        };
    }
    
}
