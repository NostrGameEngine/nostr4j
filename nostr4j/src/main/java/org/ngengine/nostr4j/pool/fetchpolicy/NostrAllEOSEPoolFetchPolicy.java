package org.ngengine.nostr4j.pool.fetchpolicy;

import java.util.List;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.sub.NostrSubAllListener;
import static org.ngengine.platform.NGEUtils.dbg;

public class NostrAllEOSEPoolFetchPolicy implements NostrPoolFetchPolicy{
    private static final Logger logger = Logger.getLogger(NostrPool.class.getName());
    private static final NostrAllEOSEPoolFetchPolicy INSTANCE = new NostrAllEOSEPoolFetchPolicy();

    public static NostrAllEOSEPoolFetchPolicy get() {
        return INSTANCE;
    }
    @Override
    public NostrSubAllListener getListener(NostrSubscription sub, List<SignedNostrEvent> events, Runnable end) {
        return new NostrSubAllListener() {

            @Override
            public void onSubEvent(SignedNostrEvent e, boolean stored) {
                assert dbg(() -> {
                    logger.finer("fetch event " + e + " for subscription " + sub.getId());
                });

                events.add(e);
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
                if (all) {
                    assert dbg(() -> {
                        logger.fine("fetch eose for fetch " + sub.getId() + " with received events: " + events);
                    });
                    end.run();
                }
            }
            
        };
    }
    
}
