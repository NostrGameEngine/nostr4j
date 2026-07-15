/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class EventTrackerScalabilityTest {

    private static final int MAXIMUM_RETAINED_EVENTS = 8_192;
    private static final int STREAM_EVENTS = 1_000_000;

    @Test
    public void retainedStateRemainsBoundedAcrossMillionEventStream() {
        InspectableEventTracker tracker = new InspectableEventTracker(MAXIMUM_RETAINED_EVENTS);
        long baseTimestamp = Instant.now().getEpochSecond() - STREAM_EVENTS;

        for (int i = 0; i < STREAM_EVENTS; i++) {
            assertFalse(tracker.seen(BenchmarkEventFactory.event(baseTimestamp + i, 10, i)));
            if (i + 1 >= MAXIMUM_RETAINED_EVENTS && (i + 1) % 10_000 == 0) {
                assertEquals(MAXIMUM_RETAINED_EVENTS, tracker.retainedEventCount());
            }
        }

        assertEquals(MAXIMUM_RETAINED_EVENTS, tracker.retainedEventCount());
        assertEquals(MAXIMUM_RETAINED_EVENTS, tracker.indexedEventCount());
    }

    @Test
    public void recurringEventStormDoesNotGrowRetainedState() {
        InspectableEventTracker tracker = new InspectableEventTracker(MAXIMUM_RETAINED_EVENTS);
        long baseTimestamp = Instant.now().getEpochSecond() - MAXIMUM_RETAINED_EVENTS;
        SignedNostrEvent mostRecent = null;

        for (int i = 0; i < MAXIMUM_RETAINED_EVENTS; i++) {
            mostRecent = BenchmarkEventFactory.event(baseTimestamp + i, 20, i);
            assertFalse(tracker.seen(mostRecent));
        }

        for (int i = 0; i < STREAM_EVENTS; i++) {
            assertTrue(tracker.seen(mostRecent));
        }

        assertEquals(MAXIMUM_RETAINED_EVENTS, tracker.retainedEventCount());
        assertEquals(MAXIMUM_RETAINED_EVENTS, tracker.indexedEventCount());
    }
}
