/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.event.tracker.ForwardSlidingWindowEventTracker;

public final class InspectableEventTracker extends ForwardSlidingWindowEventTracker {

    public InspectableEventTracker(int maximumRetainedEvents) {
        super(maximumRetainedEvents, 0, 365, TimeUnit.DAYS, 0, TimeUnit.DAYS);
    }

    public int retainedEventCount() {
        synchronized (seenEvents) {
            return count();
        }
    }

    public int indexedEventCount() {
        synchronized (seenEvents) {
            return seenEventIds.size();
        }
    }
}
